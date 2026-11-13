package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Integration Test Shards Tests")
class IntegrationTestShardsTest {

  private static final List<String> UNRECORDED_CLASSES =
      IntStream.range(0, 1_000).mapToObj(index -> "generated.Unrecorded" + index + "IT").toList();

  @Test
  @DisplayName("Should balance the recorded class times within a minute when splitting in two")
  void shouldBalanceTheRecordedClassTimesWithinAMinuteWhenSplittingInTwo() {
    var recordedSeconds = IntegrationTestShards.recordedSeconds();
    var shards = IntegrationTestShards.balancing(2, recordedSeconds);

    var secondsPerShard =
        recordedSeconds.entrySet().stream()
            .collect(
                Collectors.groupingBy(
                    entry -> shards.shardOf(entry.getKey()),
                    Collectors.summingDouble(Map.Entry::getValue)));

    assertThat(recordedSeconds).as("recorded classes").hasSizeGreaterThan(200);
    assertThat(secondsPerShard).containsOnlyKeys(1, 2);
    assertThat(Math.abs(secondsPerShard.get(1) - secondsPerShard.get(2)))
        .as("difference between the shards' recorded seconds %s", secondsPerShard)
        .isLessThanOrEqualTo(60.0);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  @DisplayName("Should spread classes missing from the record over every shard when balancing")
  void shouldSpreadClassesMissingFromTheRecordOverEveryShardWhenBalancing(int count) {
    var shards = IntegrationTestShards.balancing(count, IntegrationTestShards.recordedSeconds());

    var classesPerShard =
        UNRECORDED_CLASSES.stream()
            .collect(Collectors.groupingBy(shards::shardOf, Collectors.counting()));

    assertThat(classesPerShard)
        .as("unrecorded classes per shard")
        .containsOnlyKeys(IntStream.rangeClosed(1, count).boxed().toList());
  }

  @Test
  @DisplayName("Should assign every class the same shard when the record is read in another order")
  void shouldAssignEveryClassTheSameShardWhenTheRecordIsReadInAnotherOrder() {
    var recordedSeconds = IntegrationTestShards.recordedSeconds();
    var byName = Map.Entry.<String, Double>comparingByKey();
    var shards = IntegrationTestShards.balancing(2, inOrder(recordedSeconds, byName));
    var shardsFromReversedRecord =
        IntegrationTestShards.balancing(2, inOrder(recordedSeconds, byName.reversed()));
    var classes = new ArrayList<>(recordedSeconds.keySet());
    classes.addAll(UNRECORDED_CLASSES);

    var differing =
        classes.stream()
            .filter(name -> shards.shardOf(name) != shardsFromReversedRecord.shardOf(name))
            .toList();

    assertThat(differing).as("classes whose shard depends on the record's order").isEmpty();
  }

  private static Map<String, Double> inOrder(
      Map<String, Double> seconds, Comparator<Map.Entry<String, Double>> order) {
    var ordered = new LinkedHashMap<String, Double>();
    seconds.entrySet().stream()
        .sorted(order)
        .forEachOrdered(entry -> ordered.put(entry.getKey(), entry.getValue()));

    return ordered;
  }
}
