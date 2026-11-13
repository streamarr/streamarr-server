package com.streamarr.server.support;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

  private static final String RECORD = "integration-test-seconds.tsv";
  private static final List<String> GENERATED_CLASSES =
      IntStream.range(0, 1_000).mapToObj(index -> "generated.Generated" + index + "IT").toList();

  @Test
  @DisplayName("Should balance the recorded class times within a minute when splitting in two")
  void shouldBalanceTheRecordedClassTimesWithinAMinuteWhenSplittingInTwo() throws IOException {
    var recordedSeconds = recordedSeconds();

    var secondsPerShard =
        recordedSeconds.entrySet().stream()
            .collect(
                Collectors.groupingBy(
                    entry -> IntegrationTestShards.shardOf(entry.getKey(), 2),
                    Collectors.summingDouble(Map.Entry::getValue)));

    assertThat(recordedSeconds).as("recorded classes").hasSizeGreaterThan(200);
    assertThat(secondsPerShard).containsOnlyKeys(1, 2);
    assertThat(Math.abs(secondsPerShard.get(1) - secondsPerShard.get(2)))
        .as("difference between the shards' recorded seconds %s", secondsPerShard)
        .isLessThanOrEqualTo(60.0);
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 3, 4})
  @DisplayName("Should use every shard and no other when splitting many classes")
  void shouldUseEveryShardAndNoOtherWhenSplittingManyClasses(int count) {
    var classesPerShard =
        GENERATED_CLASSES.stream()
            .collect(
                Collectors.groupingBy(
                    name -> IntegrationTestShards.shardOf(name, count), Collectors.counting()));

    assertThat(classesPerShard)
        .as("generated classes per shard")
        .containsOnlyKeys(IntStream.rangeClosed(1, count).boxed().toList());
  }

  private static Map<String, Double> recordedSeconds() throws IOException {
    try (var resource = IntegrationTestShardsTest.class.getResourceAsStream(RECORD)) {
      assertThat(resource).as(RECORD).isNotNull();
      return new String(resource.readAllBytes(), UTF_8)
          .lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(line -> line.split("\t"))
          .collect(
              Collectors.toUnmodifiableMap(
                  fields -> fields[0], fields -> Double.parseDouble(fields[1])));
    }
  }
}
