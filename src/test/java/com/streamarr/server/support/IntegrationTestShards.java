package com.streamarr.server.support;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.NonNull;

/**
 * Splits the integration test classes into CI shards. Longest first, each recorded class goes to
 * the shard with the fewest recorded seconds so far; any other class goes to the shard its name
 * hashes to. Each class therefore has exactly one shard, and stale times cost only balance.
 */
public final class IntegrationTestShards {

  private static final String RECORD = "integration-test-seconds.tsv";
  private static final Comparator<Map.Entry<String, Double>> LONGEST_FIRST =
      Map.Entry.<String, Double>comparingByValue()
          .reversed()
          .thenComparing(Map.Entry.comparingByKey());

  private final int count;
  private final Map<String, Integer> recordedShards;

  private IntegrationTestShards(int count, Map<String, Integer> recordedShards) {
    this.count = count;
    this.recordedShards = recordedShards;
  }

  public static IntegrationTestShards balancing(
      int count, @NonNull Map<String, Double> recordedSeconds) {
    var seconds = new double[count];
    var shards = new HashMap<String, Integer>();
    for (var entry : recordedSeconds.entrySet().stream().sorted(LONGEST_FIRST).toList()) {
      var lightest =
          IntStream.range(0, count)
              .boxed()
              .min(Comparator.comparingDouble(shard -> seconds[shard]))
              .orElseThrow();
      seconds[lightest] += entry.getValue();
      shards.put(entry.getKey(), lightest + 1);
    }

    return new IntegrationTestShards(count, Map.copyOf(shards));
  }

  /** The seconds each recorded integration test class took, by fully qualified class name. */
  public static Map<String, Double> recordedSeconds() {
    try (var resource = IntegrationTestShards.class.getResourceAsStream(RECORD)) {
      assertThat(resource).as(RECORD).isNotNull();
      return new String(resource.readAllBytes(), UTF_8)
          .lines()
          .filter(line -> !line.isBlank() && !line.startsWith("#"))
          .map(line -> line.split("\t"))
          .collect(
              Collectors.toUnmodifiableMap(
                  fields -> fields[0], fields -> Double.parseDouble(fields[1])));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The shard, from 1 to the shard count, that runs the named top-level test class. */
  public int shardOf(@NonNull String testClassName) {
    return Optional.ofNullable(recordedShards.get(testClassName))
        .orElseGet(() -> Math.floorMod(testClassName.hashCode(), count) + 1);
  }
}
