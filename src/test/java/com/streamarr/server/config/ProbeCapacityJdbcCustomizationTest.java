package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.probe.PlaybackProbeClaims;
import com.streamarr.server.services.streaming.ProbeCapacity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Capacity JDBC Customization Tests")
class ProbeCapacityJdbcCustomizationTest {

  private static final String PROBE_TABLE = JdbcTaskRepository.DEFAULT_TABLE_NAME;
  private static final String SCAN_TABLE = KeyframePacketScanScheduler.TABLE_NAME;
  private static final String CONDITION = " AND task_name = 'media-probe'";
  private static final int SCHEDULER_UPPER_LIMIT = 8;
  private static final Pattern BACKGROUND_LIMIT =
      Pattern.compile(
          "(?i)order by execution_time asc, task_instance asc fetch next (\\d+) rows only\\)");
  private static final Pattern CLAIM_LIMIT = Pattern.compile("LIMIT (\\d+)$");

  private final PostgreSqlJdbcCustomization genericLockAndFetch =
      new PostgreSqlJdbcCustomization(true, false);
  private final PlaybackProbeClaims playbackProbeClaims =
      new PlaybackProbeClaims(new MutableClock(), new SimpleMeterRegistry());

  @ParameterizedTest(name = "{0}")
  @MethodSource("probeClaims")
  @DisplayName(
      "Should claim playback probes up to every free unit and other probes up to the free"
          + " background units when probes are due")
  void shouldClaimPlaybackProbesUpToEveryFreeUnitAndOtherProbesUpToTheFreeBackgroundUnitsWhenDue(
      ProbeClaimCase claimCase) {
    IntStream.range(0, claimCase.playbackExecutionsInProgress())
        .forEach(_ -> playbackProbeClaims.claimed(UUID.randomUUID()));

    var query =
        customization(claimCase.capacity())
            .createGenericSelectForUpdateQuery(PROBE_TABLE, claimCase.limit(), CONDITION, true);

    assertThat(query).startsWith(genericSelectPrefix(PROBE_TABLE));
    assertThat(limit(CLAIM_LIMIT, query)).isEqualTo(claimCase.claimed());
    assertThat(limit(BACKGROUND_LIMIT, query)).isEqualTo(claimCase.backgroundProbes());
  }

  static Stream<ProbeClaimCase> probeClaims() {
    return Stream.of(
        ProbeClaimCase.builder()
            .description("every unit free and nothing in progress")
            .limit(8)
            .capacity(free(2, 3).build())
            .claimed(5)
            .backgroundProbes(3)
            .build(),
        ProbeClaimCase.builder()
            .description("two background executions await dispatch")
            .limit(6)
            .capacity(free(2, 3).build())
            .claimed(3)
            .backgroundProbes(1)
            .build(),
        ProbeClaimCase.builder()
            .description("a playback execution awaits dispatch")
            .limit(7)
            .capacity(free(2, 3).build())
            .playbackExecutionsInProgress(1)
            .claimed(4)
            .backgroundProbes(3)
            .build(),
        ProbeClaimCase.builder()
            .description("no unit is free")
            .limit(4)
            .capacity(free(0, 0).runningProbes(4).build())
            .claimed(0)
            .backgroundProbes(0)
            .build(),
        ProbeClaimCase.builder()
            .description("the scheduler asks for fewer probes than the free units")
            .limit(2)
            .capacity(free(2, 20).build())
            .claimed(2)
            .backgroundProbes(14)
            .build());
  }

  @ParameterizedTest(name = "limit={0}, free scans={1}, running scans={2} -> {3}")
  @CsvSource({"8, 1, 0, 1", "8, 0, 1, 0", "6, 2, 0, 0", "8, 3, 0, 3"})
  @DisplayName("Should claim scans up to the free scan units when keyframe packet scans are due")
  void shouldClaimScansUpToTheFreeScanUnitsWhenKeyframePacketScansAreDue(
      int limit, int freeScans, int runningScans, int claimed) {
    var capacity =
        ProbeCapacity.builder()
            .freeKeyframePacketScanUnits(freeScans)
            .runningKeyframePacketScans(runningScans)
            .build();

    assertThat(
            customization(capacity)
                .createGenericSelectForUpdateQuery(SCAN_TABLE, limit, CONDITION, false))
        .isEqualTo(
            genericLockAndFetch.createGenericSelectForUpdateQuery(
                SCAN_TABLE, claimed, CONDITION, false));
  }

  @Test
  @DisplayName("Should keep the scheduler's limit when another scheduler claims from its own table")
  void shouldKeepTheSchedulersLimitWhenAnotherSchedulerClaimsFromItsOwnTable() {
    assertThat(
            customization(ProbeCapacity.NONE)
                .createGenericSelectForUpdateQuery("artwork_tasks", 4, CONDITION, true))
        .isEqualTo(
            genericLockAndFetch.createGenericSelectForUpdateQuery(
                "artwork_tasks", 4, CONDITION, true));
  }

  @Test
  @DisplayName("Should bind no parameter of its own when it limits the background probes")
  void shouldBindNoParameterOfItsOwnWhenItLimitsTheBackgroundProbes() {
    var query =
        customization(free(2, 3).build())
            .createGenericSelectForUpdateQuery(PROBE_TABLE, 8, CONDITION, true);
    var generic =
        genericLockAndFetch.createGenericSelectForUpdateQuery(PROBE_TABLE, 5, CONDITION, true);

    assertThat(query.chars().filter(character -> character == '?').count())
        .isEqualTo(generic.chars().filter(character -> character == '?').count());
  }

  private String genericSelectPrefix(String table) {
    var generic = genericLockAndFetch.createGenericSelectForUpdateQuery(table, 1, CONDITION, true);
    return generic.substring(0, generic.indexOf(CONDITION) + CONDITION.length());
  }

  private static int limit(Pattern pattern, String query) {
    var matcher = pattern.matcher(query.strip());
    assertThat(matcher.find()).as("The claim names its limit: %s", query).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  private static ProbeCapacity.ProbeCapacityBuilder free(int playbackUnits, int backgroundUnits) {
    return ProbeCapacity.builder()
        .freePlaybackProbeUnits(playbackUnits)
        .freeBackgroundProbeUnits(backgroundUnits);
  }

  private ProbeCapacityJdbcCustomization customization(ProbeCapacity capacity) {
    return ProbeCapacityJdbcCustomization.builder()
        .capacitySource(() -> capacity)
        .playbackProbeClaims(playbackProbeClaims)
        .probeSchedulerUpperLimit(SCHEDULER_UPPER_LIMIT)
        .scanSchedulerUpperLimit(SCHEDULER_UPPER_LIMIT)
        .build();
  }

  @Builder
  record ProbeClaimCase(
      String description,
      int limit,
      ProbeCapacity capacity,
      int playbackExecutionsInProgress,
      int claimed,
      int backgroundProbes) {

    @Override
    public String toString() {
      return description;
    }
  }
}
