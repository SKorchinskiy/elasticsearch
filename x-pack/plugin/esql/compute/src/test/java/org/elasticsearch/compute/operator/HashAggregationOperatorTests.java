/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.aggregation.AggregatorMode;
import org.elasticsearch.compute.aggregation.MaxLongAggregatorFunction;
import org.elasticsearch.compute.aggregation.MaxLongAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.MaxLongGroupingAggregatorFunctionTests;
import org.elasticsearch.compute.aggregation.SumLongAggregatorFunction;
import org.elasticsearch.compute.aggregation.SumLongAggregatorFunctionSupplier;
import org.elasticsearch.compute.aggregation.SumLongGroupingAggregatorFunctionTests;
import org.elasticsearch.compute.aggregation.blockhash.BlockHash;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BlockUtils;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.test.BlockTestUtils;
import org.elasticsearch.core.Tuple;
import org.hamcrest.Matcher;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.LongStream;

import static java.util.stream.IntStream.range;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class HashAggregationOperatorTests extends ForkingOperatorTestCase {
    @Override
    protected SourceOperator simpleInput(BlockFactory blockFactory, int size) {
        long max = randomLongBetween(1, Long.MAX_VALUE / size);
        return new TupleLongLongBlockSourceOperator(
            blockFactory,
            LongStream.range(0, size).mapToObj(l -> Tuple.tuple(l % 5, randomLongBetween(-max, max)))
        );
    }

    @Override
    protected Operator.OperatorFactory simpleWithMode(SimpleOptions options, AggregatorMode mode) {
        List<Integer> sumChannels, maxChannels;
        if (mode.isInputPartial()) {
            int sumChannelCount = SumLongAggregatorFunction.intermediateStateDesc().size();
            int maxChannelCount = MaxLongAggregatorFunction.intermediateStateDesc().size();
            sumChannels = range(1, 1 + sumChannelCount).boxed().toList();
            maxChannels = range(1 + sumChannelCount, 1 + sumChannelCount + maxChannelCount).boxed().toList();
        } else {
            sumChannels = maxChannels = List.of(1);
        }

        return new HashAggregationOperator.HashAggregationOperatorFactory(
            List.of(new BlockHash.GroupSpec(0, ElementType.LONG)),
            mode,
            List.of(
                new SumLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, sumChannels),
                new MaxLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, maxChannels)
            ),
            randomPageSize(),
            null
        );
    }

    @Override
    protected Matcher<String> expectedDescriptionOfSimple() {
        return equalTo("HashAggregationOperator[mode = <not-needed>, aggs = sum of longs, max of longs]");
    }

    @Override
    protected Matcher<String> expectedToStringOfSimple() {
        return equalTo(
            "HashAggregationOperator[blockHash=LongBlockHash{channel=0, entries=0, seenNull=false}, aggregators=["
                + "GroupingAggregator[aggregatorFunction=SumLongGroupingAggregatorFunction[channels=[1]], mode=SINGLE], "
                + "GroupingAggregator[aggregatorFunction=MaxLongGroupingAggregatorFunction[channels=[1]], mode=SINGLE]]]"
        );
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        assertThat(results, hasSize(1));
        assertThat(results.get(0).getBlockCount(), equalTo(3));
        assertThat(results.get(0).getPositionCount(), equalTo(5));

        SumLongGroupingAggregatorFunctionTests sum = new SumLongGroupingAggregatorFunctionTests();
        MaxLongGroupingAggregatorFunctionTests max = new MaxLongGroupingAggregatorFunctionTests();

        LongBlock groups = results.get(0).getBlock(0);
        Block sums = results.get(0).getBlock(1);
        Block maxs = results.get(0).getBlock(2);
        for (int i = 0; i < 5; i++) {
            long group = groups.getLong(i);
            sum.assertSimpleGroup(input, sums, i, group);
            max.assertSimpleGroup(input, maxs, i, group);
        }
    }

    public void testTopNNullsLast() {
        boolean ascOrder = randomBoolean();
        var groups = new Long[] { 0L, 10L, 20L, 30L, 40L, 50L };
        if (ascOrder) {
            Arrays.sort(groups, Comparator.reverseOrder());
        }
        var mode = AggregatorMode.SINGLE;
        var groupChannel = 0;
        var aggregatorChannels = List.of(1);

        try (
            var operator = new HashAggregationOperator.HashAggregationOperatorFactory(
                List.of(new BlockHash.GroupSpec(groupChannel, ElementType.LONG, null, new BlockHash.TopNDef(0, ascOrder, false, 3))),
                mode,
                List.of(
                    new SumLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, aggregatorChannels),
                    new MaxLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, aggregatorChannels)
                ),
                randomPageSize(),
                null
            ).get(driverContext())
        ) {
            var page = new Page(
                BlockUtils.fromList(
                    blockFactory(),
                    List.of(
                        List.of(groups[1], 2L),
                        Arrays.asList(null, 1L),
                        List.of(groups[2], 4L),
                        List.of(groups[3], 8L),
                        List.of(groups[3], 16L)
                    )
                )
            );
            operator.addInput(page);

            page = new Page(
                BlockUtils.fromList(
                    blockFactory(),
                    List.of(
                        List.of(groups[5], 64L),
                        List.of(groups[4], 32L),
                        List.of(List.of(groups[1], groups[5]), 128L),
                        List.of(groups[0], 256L),
                        Arrays.asList(null, 512L)
                    )
                )
            );
            operator.addInput(page);

            operator.finish();

            var outputPage = operator.getOutput();

            var groupsBlock = (LongBlock) outputPage.getBlock(0);
            var sumBlock = (LongBlock) outputPage.getBlock(1);
            var maxBlock = (LongBlock) outputPage.getBlock(2);

            assertThat(groupsBlock.getPositionCount(), equalTo(3));
            assertThat(sumBlock.getPositionCount(), equalTo(3));
            assertThat(maxBlock.getPositionCount(), equalTo(3));

            assertThat(groupsBlock.getTotalValueCount(), equalTo(3));
            assertThat(sumBlock.getTotalValueCount(), equalTo(3));
            assertThat(maxBlock.getTotalValueCount(), equalTo(3));

            assertThat(
                BlockTestUtils.valuesAtPositions(groupsBlock, 0, 3),
                equalTo(List.of(List.of(groups[3]), List.of(groups[5]), List.of(groups[4])))
            );
            assertThat(BlockTestUtils.valuesAtPositions(sumBlock, 0, 3), equalTo(List.of(List.of(24L), List.of(192L), List.of(32L))));
            assertThat(BlockTestUtils.valuesAtPositions(maxBlock, 0, 3), equalTo(List.of(List.of(16L), List.of(128L), List.of(32L))));

            outputPage.releaseBlocks();
        }
    }

    public void testTopNNullsFirst() {
        boolean ascOrder = randomBoolean();
        var groups = new Long[] { 0L, 10L, 20L, 30L, 40L, 50L };
        if (ascOrder) {
            Arrays.sort(groups, Comparator.reverseOrder());
        }
        var mode = AggregatorMode.SINGLE;
        var groupChannel = 0;
        var aggregatorChannels = List.of(1);

        try (
            var operator = new HashAggregationOperator.HashAggregationOperatorFactory(
                List.of(new BlockHash.GroupSpec(groupChannel, ElementType.LONG, null, new BlockHash.TopNDef(0, ascOrder, true, 3))),
                mode,
                List.of(
                    new SumLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, aggregatorChannels),
                    new MaxLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, aggregatorChannels)
                ),
                randomPageSize(),
                null
            ).get(driverContext())
        ) {
            var page = new Page(
                BlockUtils.fromList(
                    blockFactory(),
                    List.of(
                        List.of(groups[1], 2L),
                        Arrays.asList(null, 1L),
                        List.of(groups[2], 4L),
                        List.of(groups[3], 8L),
                        List.of(groups[3], 16L)
                    )
                )
            );
            operator.addInput(page);

            page = new Page(
                BlockUtils.fromList(
                    blockFactory(),
                    List.of(
                        List.of(groups[5], 64L),
                        List.of(groups[4], 32L),
                        List.of(List.of(groups[1], groups[5]), 128L),
                        List.of(groups[0], 256L),
                        Arrays.asList(null, 512L)
                    )
                )
            );
            operator.addInput(page);

            operator.finish();

            var outputPage = operator.getOutput();

            var groupsBlock = (LongBlock) outputPage.getBlock(0);
            var sumBlock = (LongBlock) outputPage.getBlock(1);
            var maxBlock = (LongBlock) outputPage.getBlock(2);

            assertThat(groupsBlock.getPositionCount(), equalTo(3));
            assertThat(sumBlock.getPositionCount(), equalTo(3));
            assertThat(maxBlock.getPositionCount(), equalTo(3));

            assertThat(groupsBlock.getTotalValueCount(), equalTo(2));
            assertThat(sumBlock.getTotalValueCount(), equalTo(3));
            assertThat(maxBlock.getTotalValueCount(), equalTo(3));

            assertThat(
                BlockTestUtils.valuesAtPositions(groupsBlock, 0, 3),
                equalTo(Arrays.asList(null, List.of(groups[5]), List.of(groups[4])))
            );
            assertThat(BlockTestUtils.valuesAtPositions(sumBlock, 0, 3), equalTo(List.of(List.of(513L), List.of(192L), List.of(32L))));
            assertThat(BlockTestUtils.valuesAtPositions(maxBlock, 0, 3), equalTo(List.of(List.of(512L), List.of(128L), List.of(32L))));

            outputPage.releaseBlocks();
        }
    }

    /**
     * When in intermediate/final mode, it will receive intermediate outputs that may have to be discarded
     * (TopN in the datanode but not acceptable in the coordinator).
     * <p>
     *     This test ensures that such discarding works correctly.
     * </p>
     */
    public void testTopNNullsIntermediateDiscards() {
        boolean ascOrder = randomBoolean();
        var groups = new Long[] { 0L, 10L, 20L, 30L, 40L, 50L };
        if (ascOrder) {
            Arrays.sort(groups, Comparator.reverseOrder());
        }
        var groupChannel = 0;

        // Supplier of operators to ensure that they're identical, simulating a datanode/coordinator connection
        Function<AggregatorMode, Operator> makeAggWithMode = (mode) -> {
            var sumAggregatorChannels = mode.isInputPartial() ? List.of(1, 2) : List.of(1);
            var maxAggregatorChannels = mode.isInputPartial() ? List.of(3, 4) : List.of(1);

            return new HashAggregationOperator.HashAggregationOperatorFactory(
                List.of(new BlockHash.GroupSpec(groupChannel, ElementType.LONG, null, new BlockHash.TopNDef(0, ascOrder, false, 3))),
                mode,
                List.of(
                    new SumLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, sumAggregatorChannels),
                    new MaxLongAggregatorFunctionSupplier().groupingAggregatorFactory(mode, maxAggregatorChannels)
                ),
                randomPageSize(),
                null
            ).get(driverContext());
        };

        // The operator that will collect all the results
        try (var collectingOperator = makeAggWithMode.apply(AggregatorMode.FINAL)) {
            // First datanode, sending a suitable TopN set of data
            try (var datanodeOperator = makeAggWithMode.apply(AggregatorMode.INITIAL)) {
                var page = new Page(
                    BlockUtils.fromList(blockFactory(), List.of(List.of(groups[4], 1L), List.of(groups[3], 2L), List.of(groups[2], 4L)))
                );
                datanodeOperator.addInput(page);
                datanodeOperator.finish();

                var outputPage = datanodeOperator.getOutput();
                collectingOperator.addInput(outputPage);
            }

            // Second datanode, sending an outdated TopN, as the coordinator has better top values already
            try (var datanodeOperator = makeAggWithMode.apply(AggregatorMode.INITIAL)) {
                var page = new Page(
                    BlockUtils.fromList(
                        blockFactory(),
                        List.of(
                            List.of(groups[5], 8L),
                            List.of(groups[3], 16L),
                            List.of(groups[1], 32L) // This group is worse than the worst group in the coordinator
                        )
                    )
                );
                datanodeOperator.addInput(page);
                datanodeOperator.finish();

                var outputPage = datanodeOperator.getOutput();
                collectingOperator.addInput(outputPage);
            }

            collectingOperator.finish();

            var outputPage = collectingOperator.getOutput();

            var groupsBlock = (LongBlock) outputPage.getBlock(0);
            var sumBlock = (LongBlock) outputPage.getBlock(1);
            var maxBlock = (LongBlock) outputPage.getBlock(2);

            assertThat(groupsBlock.getPositionCount(), equalTo(3));
            assertThat(sumBlock.getPositionCount(), equalTo(3));
            assertThat(maxBlock.getPositionCount(), equalTo(3));

            assertThat(groupsBlock.getTotalValueCount(), equalTo(3));
            assertThat(sumBlock.getTotalValueCount(), equalTo(3));
            assertThat(maxBlock.getTotalValueCount(), equalTo(3));

            assertThat(
                BlockTestUtils.valuesAtPositions(groupsBlock, 0, 3),
                equalTo(Arrays.asList(List.of(groups[4]), List.of(groups[3]), List.of(groups[5])))
            );
            assertThat(BlockTestUtils.valuesAtPositions(sumBlock, 0, 3), equalTo(List.of(List.of(1L), List.of(18L), List.of(8L))));
            assertThat(BlockTestUtils.valuesAtPositions(maxBlock, 0, 3), equalTo(List.of(List.of(1L), List.of(16L), List.of(8L))));

            outputPage.releaseBlocks();
        }
    }
}
