/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import org.apache.druid.data.input.InputRowSchema;
import org.apache.druid.data.input.impl.DimensionSchema;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.MapInputRowParser;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryRunnerTestHelper;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.aggregation.Aggregator;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.BufferAggregator;
import org.apache.druid.query.aggregation.DoubleSumAggregatorFactory;
import org.apache.druid.query.aggregation.NoopAggregator;
import org.apache.druid.query.aggregation.NoopBufferAggregator;
import org.apache.druid.query.aggregation.hyperloglog.HyperUniquesAggregatorFactory;
import org.apache.druid.query.metadata.metadata.ColumnAnalysis;
import org.apache.druid.query.metadata.metadata.SegmentAnalysis;
import org.apache.druid.query.metadata.metadata.SegmentMetadataQuery;
import org.apache.druid.query.spec.LegacySegmentSpec;
import org.apache.druid.segment.ColumnSelectorFactory;
import org.apache.druid.segment.IncrementalIndexSegment;
import org.apache.druid.segment.IndexBuilder;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.QueryableIndexSegment;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.TestIndex;
import org.apache.druid.segment.column.ColumnBuilder;
import org.apache.druid.segment.column.ColumnCapabilitiesImpl;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.StringUtf8DictionaryEncodedColumn;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.data.ListIndexed;
import org.apache.druid.segment.data.ObjectStrategy;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.segment.incremental.OnheapIncrementalIndex;
import org.apache.druid.segment.serde.ComplexMetricExtractor;
import org.apache.druid.segment.serde.ComplexMetricSerde;
import org.apache.druid.segment.serde.ComplexMetrics;
import org.apache.druid.testing.InitializedNullHandlingTest;
import org.apache.druid.timeline.SegmentId;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public class SegmentAnalyzerTest extends InitializedNullHandlingTest
{
  private static final EnumSet<SegmentMetadataQuery.AnalysisType> EMPTY_ANALYSES =
      EnumSet.noneOf(SegmentMetadataQuery.AnalysisType.class);

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void testIncrementalWorks()
  {
    testIncrementalWorksHelper(null);
    testIncrementalWorksHelper(EMPTY_ANALYSES);
  }

  private void testIncrementalWorksHelper(EnumSet<SegmentMetadataQuery.AnalysisType> analyses)
  {
    final List<SegmentAnalysis> results = getSegmentAnalysises(
        new IncrementalIndexSegment(TestIndex.getIncrementalTestIndex(), SegmentId.dummy("ds")),
        analyses
    );

    Assert.assertEquals(1, results.size());

    final SegmentAnalysis analysis = results.get(0);
    Assert.assertEquals(SegmentId.dummy("ds").toString(), analysis.getId());

    final Map<String, ColumnAnalysis> columns = analysis.getColumns();

    Assert.assertEquals(
        TestIndex.COLUMNS.length + 3,
        columns.size()
    ); // All columns including time and empty/null column

    // Verify key order is the same as the underlying segment.
    // This helps DruidSchema keep things in the proper order when it does SegmentMetadata queries.
    final List<Map.Entry<String, ColumnAnalysis>> entriesInOrder = new ArrayList<>(columns.entrySet());

    Assert.assertEquals(ColumnHolder.TIME_COLUMN_NAME, entriesInOrder.get(0).getKey());
    Assert.assertEquals(ColumnType.LONG, entriesInOrder.get(0).getValue().getTypeSignature());

    // Start from 1: skipping __time
    for (int i = 0; i < TestIndex.DIMENSION_SCHEMAS.size(); i++) {
      final DimensionSchema schema = TestIndex.DIMENSION_SCHEMAS.get(i);
      final Map.Entry<String, ColumnAnalysis> analysisEntry = entriesInOrder.get(i + 1 /* skip __time */);
      final String dimension = schema.getName();
      Assert.assertEquals(dimension, analysisEntry.getKey());
      final ColumnAnalysis columnAnalysis = analysisEntry.getValue();
      final boolean isString = schema.getColumnType().is(ValueType.STRING);

      Assert.assertEquals(dimension, schema.getColumnType().toString(), columnAnalysis.getType());
      Assert.assertEquals(dimension, 0, columnAnalysis.getSize());
      if (isString) {
        if (analyses == null) {
          Assert.assertTrue(dimension, columnAnalysis.getCardinality() > 0);
        } else {
          Assert.assertEquals(dimension, 0, columnAnalysis.getCardinality().longValue());
        }
      } else {
        Assert.assertNull(dimension, columnAnalysis.getCardinality());
      }
    }

    for (String metric : TestIndex.DOUBLE_METRICS) {
      final ColumnAnalysis columnAnalysis = columns.get(metric);
      Assert.assertEquals(metric, ValueType.DOUBLE.name(), columnAnalysis.getType());
      Assert.assertEquals(metric, 0, columnAnalysis.getSize());
      Assert.assertNull(metric, columnAnalysis.getCardinality());
    }

    for (String metric : TestIndex.FLOAT_METRICS) {
      final ColumnAnalysis columnAnalysis = columns.get(metric);
      Assert.assertEquals(metric, ValueType.FLOAT.name(), columnAnalysis.getType());
      Assert.assertEquals(metric, 0, columnAnalysis.getSize());
      Assert.assertNull(metric, columnAnalysis.getCardinality());
    }
  }

  @Test
  public void testMappedWorks()
  {
    testMappedWorksHelper(null);
    testMappedWorksHelper(EMPTY_ANALYSES);
  }

  private void testMappedWorksHelper(EnumSet<SegmentMetadataQuery.AnalysisType> analyses)
  {
    final List<SegmentAnalysis> results = getSegmentAnalysises(
        new QueryableIndexSegment(TestIndex.getMMappedTestIndex(), SegmentId.dummy("test_1")),
        analyses
    );

    Assert.assertEquals(1, results.size());

    final SegmentAnalysis analysis = results.get(0);
    Assert.assertEquals(SegmentId.dummy("test_1").toString(), analysis.getId());

    final Map<String, ColumnAnalysis> columns = analysis.getColumns();
    // Verify key order is the same as the underlying segment.
    // This helps DruidSchema keep things in the proper order when it does SegmentMetadata queries.
    final List<Map.Entry<String, ColumnAnalysis>> entriesInOrder = new ArrayList<>(columns.entrySet());

    Assert.assertEquals(ColumnHolder.TIME_COLUMN_NAME, entriesInOrder.get(0).getKey());
    Assert.assertEquals(ColumnType.LONG, entriesInOrder.get(0).getValue().getTypeSignature());

    // Start from 1: skipping __time
    for (int i = 0; i < TestIndex.DIMENSION_SCHEMAS.size(); i++) {
      final DimensionSchema schema = TestIndex.DIMENSION_SCHEMAS.get(i);
      final Map.Entry<String, ColumnAnalysis> analysisEntry = entriesInOrder.get(i + 1 /* skip __time */);
      final String dimension = schema.getName();
      Assert.assertEquals(dimension, analysisEntry.getKey());
      final ColumnAnalysis columnAnalysis = analysisEntry.getValue();
      final boolean isString = schema.getColumnType().is(ValueType.STRING);
      Assert.assertEquals(dimension, schema.getColumnType().toString(), columnAnalysis.getType());
      Assert.assertEquals(dimension, 0, columnAnalysis.getSize());

      if (isString) {
        if (analyses == null) {
          Assert.assertTrue(dimension, columnAnalysis.getCardinality() > 0);
        } else {
          Assert.assertEquals(dimension, 0, columnAnalysis.getCardinality().longValue());
        }
      } else {
        Assert.assertNull(dimension, columnAnalysis.getCardinality());
      }
    }

    for (String metric : TestIndex.DOUBLE_METRICS) {
      final ColumnAnalysis columnAnalysis = columns.get(metric);

      Assert.assertEquals(metric, ValueType.DOUBLE.name(), columnAnalysis.getType());
      Assert.assertEquals(metric, 0, columnAnalysis.getSize());
      Assert.assertNull(metric, columnAnalysis.getCardinality());
    }

    for (String metric : TestIndex.FLOAT_METRICS) {
      final ColumnAnalysis columnAnalysis = columns.get(metric);
      Assert.assertEquals(metric, ValueType.FLOAT.name(), columnAnalysis.getType());
      Assert.assertEquals(metric, 0, columnAnalysis.getSize());
      Assert.assertNull(metric, columnAnalysis.getCardinality());
    }
  }

  /**
   * *Awesome* method name auto-generated by IntelliJ!  I love IntelliJ!
   *
   * @param index
   *
   * @return
   */
  private List<SegmentAnalysis> getSegmentAnalysises(Segment index, EnumSet<SegmentMetadataQuery.AnalysisType> analyses)
  {
    final QueryRunner runner = QueryRunnerTestHelper.makeQueryRunner(
        (QueryRunnerFactory) new SegmentMetadataQueryRunnerFactory(
            new SegmentMetadataQueryQueryToolChest(new SegmentMetadataQueryConfig()),
            QueryRunnerTestHelper.NOOP_QUERYWATCHER
        ),
        index,
        null
    );

    final SegmentMetadataQuery query = new SegmentMetadataQuery(
        new TableDataSource("test"), new LegacySegmentSpec("2011/2012"), null, null, null, analyses, false, false
    );
    return runner.run(QueryPlus.wrap(query)).toList();
  }

  static {
    ComplexMetrics.registerSerde(InvalidAggregatorFactory.TYPE, new ComplexMetricSerde()
    {
      @Override
      public String getTypeName()
      {
        return InvalidAggregatorFactory.TYPE;
      }

      @Override
      public ComplexMetricExtractor getExtractor()
      {
        return null;
      }

      @Override
      public void deserializeColumn(ByteBuffer buffer, ColumnBuilder builder)
      {

      }

      @Override
      public ObjectStrategy getObjectStrategy()
      {
        return DummyObjectStrategy.getInstance();
      }
    });
  }

  /**
   * This test verifies that if a segment was created using an unknown/invalid aggregator
   * (which can happen if an aggregator was removed for a later version), then,
   * analyzing the segment doesn't fail and the result of analysis of the complex column
   * is reported as an error.
   *
   * @throws IOException
   */
  @Test
  public void testAnalyzingSegmentWithNonExistentAggregator() throws IOException
  {
    final URL resource = SegmentAnalyzerTest.class.getClassLoader().getResource("druid.sample.numeric.tsv");
    CharSource source = Resources.asByteSource(resource).asCharSource(StandardCharsets.UTF_8);
    String invalid_aggregator = "invalid_aggregator";
    AggregatorFactory[] metrics = new AggregatorFactory[]{
        new DoubleSumAggregatorFactory(TestIndex.DOUBLE_METRICS[0], "index"),
        new HyperUniquesAggregatorFactory("quality_uniques", "quality"),
        new InvalidAggregatorFactory(invalid_aggregator, "quality")
    };
    final IncrementalIndexSchema schema = new IncrementalIndexSchema.Builder()
        .withMinTimestamp(DateTimes.of("2011-01-12T00:00:00.000Z").getMillis())
        .withTimestampSpec(new TimestampSpec("ds", "auto", null))
        .withDimensionsSpec(TestIndex.DIMENSIONS_SPEC)
        .withMetrics(metrics)
        .withRollup(true)
        .build();

    final IncrementalIndex retVal = new OnheapIncrementalIndex.Builder()
        .setIndexSchema(schema)
        .setMaxRowCount(10000)
        .build();
    IncrementalIndex incrementalIndex = TestIndex.loadIncrementalIndex(retVal, source);

    // Analyze the in-memory segment.
    {
      SegmentAnalyzer analyzer = new SegmentAnalyzer(EnumSet.of(SegmentMetadataQuery.AnalysisType.SIZE));
      IncrementalIndexSegment segment = new IncrementalIndexSegment(incrementalIndex, SegmentId.dummy("ds"));
      Map<String, ColumnAnalysis> analyses = analyzer.analyze(segment);
      ColumnAnalysis columnAnalysis = analyses.get(invalid_aggregator);
      Assert.assertFalse(columnAnalysis.isError());
      Assert.assertEquals("invalid_complex_column_type", columnAnalysis.getType());
      Assert.assertEquals(ColumnType.ofComplex("invalid_complex_column_type"), columnAnalysis.getTypeSignature());
    }

    // Persist the index.
    final File segmentFile = TestIndex.INDEX_MERGER.persist(
        incrementalIndex,
        temporaryFolder.newFolder(),
        TestIndex.INDEX_SPEC,
        null
    );

    // Unload the complex serde, then analyze the persisted segment.
    ComplexMetrics.unregisterSerde(InvalidAggregatorFactory.TYPE);
    {
      SegmentAnalyzer analyzer = new SegmentAnalyzer(EnumSet.of(SegmentMetadataQuery.AnalysisType.SIZE));
      QueryableIndexSegment segment = new QueryableIndexSegment(
          TestIndex.INDEX_IO.loadIndex(segmentFile),
          SegmentId.dummy("ds")
      );
      Map<String, ColumnAnalysis> analyses = analyzer.analyze(segment);
      ColumnAnalysis invalidColumnAnalysis = analyses.get(invalid_aggregator);
      Assert.assertTrue(invalidColumnAnalysis.isError());
      Assert.assertEquals("error:unknown_complex_invalid_complex_column_type", invalidColumnAnalysis.getErrorMessage());

      // Run a segment metadata query also to verify it doesn't break
      final List<SegmentAnalysis> results = getSegmentAnalysises(
          segment,
          EnumSet.of(SegmentMetadataQuery.AnalysisType.SIZE)
      );
      for (SegmentAnalysis result : results) {
        Assert.assertTrue(result.getColumns().get(invalid_aggregator).isError());
      }
    }
  }

  @Test
  public void testAnalysisNullAutoDiscoveredColumn() throws IOException
  {
    IndexBuilder bob = IndexBuilder.create();
    bob.tmpDir(temporaryFolder.newFolder());
    bob.writeNullColumns(true);
    InputRowSchema schema = new InputRowSchema(
        new TimestampSpec("time", null, null),
        DimensionsSpec.builder().useSchemaDiscovery(true).build(),
        null
    );
    bob.schema(IncrementalIndexSchema.builder()
                                     .withTimestampSpec(schema.getTimestampSpec())
                                     .withDimensionsSpec(schema.getDimensionsSpec())
                                     .build());
    bob.rows(ImmutableList.of(
        MapInputRowParser.parse(schema, TestHelper.makeMapWithExplicitNull("time", 1234L, "x", null)))
    );

    QueryableIndex queryableIndex = bob.buildMMappedIndex();
    Segment s = new QueryableIndexSegment(queryableIndex, SegmentId.dummy("test"));

    SegmentAnalyzer analyzer = new SegmentAnalyzer(EMPTY_ANALYSES);
    Map<String, ColumnAnalysis> analysis = analyzer.analyze(s);
    Assert.assertEquals(ColumnType.STRING, analysis.get("x").getTypeSignature());
    Assert.assertFalse(analysis.get("x").isError());
  }

  @Test
  public void testAnalysisAutoNullColumn() throws IOException
  {
    IndexBuilder bob = IndexBuilder.create();
    bob.tmpDir(temporaryFolder.newFolder());
    bob.writeNullColumns(true);
    InputRowSchema schema = new InputRowSchema(
        new TimestampSpec("time", null, null),
        DimensionsSpec.builder().useSchemaDiscovery(true).build(),
        null
    );
    bob.schema(IncrementalIndexSchema.builder()
                                     .withTimestampSpec(schema.getTimestampSpec())
                                     .withDimensionsSpec(schema.getDimensionsSpec())
                                     .build());
    bob.rows(ImmutableList.of(
        MapInputRowParser.parse(schema, TestHelper.makeMapWithExplicitNull("time", 1234L, "x", null)))
    );

    QueryableIndex queryableIndex = bob.buildMMappedIndex();
    Segment s = new QueryableIndexSegment(queryableIndex, SegmentId.dummy("test"));

    SegmentAnalyzer analyzer = new SegmentAnalyzer(EMPTY_ANALYSES);
    Map<String, ColumnAnalysis> analysis = analyzer.analyze(s);
    Assert.assertEquals(ColumnType.STRING, analysis.get("x").getTypeSignature());
    Assert.assertFalse(analysis.get("x").isError());
  }

  @Test
  public void testAnalysisImproperComplex() throws IOException
  {
    QueryableIndex mockIndex = EasyMock.createMock(QueryableIndex.class);
    EasyMock.expect(mockIndex.getNumRows()).andReturn(100).atLeastOnce();
    EasyMock.expect(mockIndex.getColumnNames()).andReturn(Collections.singletonList("x")).atLeastOnce();
    EasyMock.expect(mockIndex.getAvailableDimensions())
            .andReturn(new ListIndexed<>(Collections.singletonList("x")))
            .atLeastOnce();
    EasyMock.expect(mockIndex.getColumnCapabilities(ColumnHolder.TIME_COLUMN_NAME))
            .andReturn(ColumnCapabilitiesImpl.createDefault().setType(ColumnType.LONG))
            .atLeastOnce();
    EasyMock.expect(mockIndex.getColumnCapabilities("x"))
            .andReturn(ColumnCapabilitiesImpl.createDefault().setType(ColumnType.UNKNOWN_COMPLEX))
            .atLeastOnce();

    ColumnHolder holder = EasyMock.createMock(ColumnHolder.class);
    EasyMock.expect(mockIndex.getColumnHolder("x")).andReturn(holder).atLeastOnce();

    StringUtf8DictionaryEncodedColumn dictionaryEncodedColumn = EasyMock.createMock(StringUtf8DictionaryEncodedColumn.class);
    EasyMock.expect(holder.getColumn()).andReturn(dictionaryEncodedColumn).atLeastOnce();

    dictionaryEncodedColumn.close();
    EasyMock.expectLastCall();
    EasyMock.replay(mockIndex, holder, dictionaryEncodedColumn);

    Segment s = new QueryableIndexSegment(mockIndex, SegmentId.dummy("test"));

    SegmentAnalyzer analyzer = new SegmentAnalyzer(EMPTY_ANALYSES);
    Map<String, ColumnAnalysis> analysis = analyzer.analyze(s);
    Assert.assertEquals(ColumnType.UNKNOWN_COMPLEX, analysis.get("x").getTypeSignature());
    Assert.assertTrue(analysis.get("x").isError());
    Assert.assertTrue(analysis.get("x").getErrorMessage().contains("is not a [org.apache.druid.segment.column.ComplexColumn]"));

    EasyMock.verify(mockIndex, holder, dictionaryEncodedColumn);
  }


  private static final class DummyObjectStrategy implements ObjectStrategy
  {

    private static final Object TO_RETURN = new Object();
    private static final DummyObjectStrategy INSTANCE = new DummyObjectStrategy();

    private static DummyObjectStrategy getInstance()
    {
      return INSTANCE;
    }

    @Override
    public Class getClazz()
    {
      return Object.class;
    }

    @Nullable
    @Override
    public Object fromByteBuffer(ByteBuffer buffer, int numBytes)
    {
      return TO_RETURN;
    }

    @Nullable
    @Override
    public byte[] toBytes(@Nullable Object val)
    {
      return new byte[0];
    }

    @Override
    public int compare(Object o1, Object o2)
    {
      return 0;
    }
  }

  /**
   * Aggregator factory not registered in AggregatorModules and hence not visible in DefaultObjectMapper.
   */
  private static class InvalidAggregatorFactory extends AggregatorFactory
  {
    private final String name;
    private final String fieldName;
    private static final String TYPE = "invalid_complex_column_type";


    public InvalidAggregatorFactory(String name, String fieldName)
    {
      this.name = name;
      this.fieldName = fieldName;
    }

    @Override
    public Aggregator factorize(ColumnSelectorFactory metricFactory)
    {
      return NoopAggregator.instance();
    }

    @Override
    public BufferAggregator factorizeBuffered(ColumnSelectorFactory metricFactory)
    {
      return NoopBufferAggregator.instance();
    }

    @Override
    public Comparator getComparator()
    {
      return null;
    }

    @Nullable
    @Override
    public Object combine(@Nullable Object lhs, @Nullable Object rhs)
    {
      return null;
    }

    @Override
    public AggregatorFactory getCombiningFactory()
    {
      return null;
    }

    @Override
    public List<AggregatorFactory> getRequiredColumns()
    {
      return null;
    }

    @Override
    public Object deserialize(Object object)
    {
      return null;
    }

    @Nullable
    @Override
    public Object finalizeComputation(@Nullable Object object)
    {
      return null;
    }

    @Override
    public String getName()
    {
      return name;
    }

    @Override
    public List<String> requiredFields()
    {
      return Collections.singletonList(fieldName);
    }

    @Override
    public ColumnType getIntermediateType()
    {
      return new ColumnType(ValueType.COMPLEX, TYPE, null);
    }

    @Override
    public int getMaxIntermediateSize()
    {
      return 0;
    }

    @Override
    public AggregatorFactory withName(String newName)
    {
      return new InvalidAggregatorFactory(newName, fieldName);
    }

    @Override
    public byte[] getCacheKey()
    {
      return new byte[0];
    }

    @Override
    public ColumnType getResultType()
    {
      return getIntermediateType();
    }
  }
}
