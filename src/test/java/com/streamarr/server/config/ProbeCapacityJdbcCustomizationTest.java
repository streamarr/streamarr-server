package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.streamarr.server.services.streaming.ProbeCapacity;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @ParameterizedTest(
      name =
          "limit={0}, free playback={1}, free background={2}, running={3} -> {4} claimed, {5}"
              + " background")
  @CsvSource({
    "8, 2, 3, 0, 5, 3",
    "6, 2, 3, 0, 1, 1",
    "8, 2, 0, 4, 2, 0",
    "8, 0, 0, 4, 0, 0",
    "2, 2, 20, 0, 2, 14"
  })
  @DisplayName(
      "Should claim playback probes up to every free unit and other probes up to the free"
          + " background units when probes are due")
  void shouldClaimPlaybackProbesUpToEveryFreeUnitAndOtherProbesUpToTheFreeBackgroundUnits(
      int limit,
      int freePlayback,
      int freeBackground,
      int running,
      int claimed,
      int backgroundClaim) {
    var capacity =
        ProbeCapacity.builder()
            .freePlaybackProbeUnits(freePlayback)
            .freeBackgroundProbeUnits(freeBackground)
            .runningProbes(running)
            .build();

    var query =
        customization(capacity)
            .createGenericSelectForUpdateQuery(PROBE_TABLE, limit, CONDITION, true);

    assertThat(limit(CLAIM_LIMIT, query)).isEqualTo(claimed);
    assertThat(limit(BACKGROUND_LIMIT, query)).isEqualTo(backgroundClaim);
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

  private static int limit(Pattern pattern, String query) {
    var matcher = pattern.matcher(query.strip());
    assertThat(matcher.find()).as("The claim names its limit: %s", query).isTrue();
    return Integer.parseInt(matcher.group(1));
  }

  private static ProbeCapacityJdbcCustomization customization(ProbeCapacity capacity) {
    return ProbeCapacityJdbcCustomization.builder()
        .capacitySource(() -> capacity)
        .probeSchedulerUpperLimit(SCHEDULER_UPPER_LIMIT)
        .scanSchedulerUpperLimit(SCHEDULER_UPPER_LIMIT)
        .build();
  }
}
