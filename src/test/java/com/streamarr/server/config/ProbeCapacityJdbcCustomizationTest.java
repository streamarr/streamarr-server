package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.jdbc.JdbcTaskRepository;
import com.github.kagkarlsson.scheduler.jdbc.PostgreSqlJdbcCustomization;
import com.streamarr.server.services.streaming.ProbeCapacity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Probe Capacity JDBC Customization Tests")
class ProbeCapacityJdbcCustomizationTest {

  private static final String PROBE_TABLE = JdbcTaskRepository.DEFAULT_TABLE_NAME;
  private static final String CONDITION = " AND task_name = 'media-probe'";
  private static final int SCHEDULER_UPPER_LIMIT = 8;

  private final PostgreSqlJdbcCustomization genericLockAndFetch =
      new PostgreSqlJdbcCustomization(true, false);

  @ParameterizedTest(name = "limit={0}, advertised={1}, running={2}, packet scans={3}")
  @CsvSource({
    "8, 3, 0, 0, 3",
    "6, 3, 0, 0, 1",
    "6, 4, 1, 1, 1",
    "8, 4, 3, 0, 1",
    "5, 3, 3, 0, 0",
    "2, 20, 0, 0, 2"
  })
  @DisplayName(
      "Should pick no more probes than the workers' free probe capacity when probes are due")
  void shouldPickNoMoreProbesThanTheWorkersFreeProbeCapacityWhenProbesAreDue(
      int limit, int advertised, int running, int runningPacketScans, int picked) {
    var capacity =
        ProbeCapacity.builder()
            .advertised(advertised)
            .running(running)
            .runningPacketScans(runningPacketScans)
            .build();
    var customization = new ProbeCapacityJdbcCustomization(() -> capacity, SCHEDULER_UPPER_LIMIT);

    assertThat(
            customization.createGenericSelectForUpdateQuery(PROBE_TABLE, limit, CONDITION, false))
        .isEqualTo(
            genericLockAndFetch.createGenericSelectForUpdateQuery(
                PROBE_TABLE, picked, CONDITION, false));
  }

  @Test
  @DisplayName("Should pick no probe when no connected worker can run probes")
  void shouldPickNoProbeWhenNoConnectedWorkerCanRunProbes() {
    var customization =
        new ProbeCapacityJdbcCustomization(
            () -> ProbeCapacity.builder().build(), SCHEDULER_UPPER_LIMIT);

    assertThat(customization.createGenericSelectForUpdateQuery(PROBE_TABLE, 8, CONDITION, false))
        .isEqualTo(
            genericLockAndFetch.createGenericSelectForUpdateQuery(
                PROBE_TABLE, 0, CONDITION, false));
  }

  @Test
  @DisplayName("Should keep the scheduler's limit when another scheduler picks from its own table")
  void shouldKeepTheSchedulersLimitWhenAnotherSchedulerPicksFromItsOwnTable() {
    var customization =
        new ProbeCapacityJdbcCustomization(
            () -> ProbeCapacity.builder().advertised(1).running(1).build(), SCHEDULER_UPPER_LIMIT);

    assertThat(customization.createGenericSelectForUpdateQuery("artwork_tasks", 4, CONDITION, true))
        .isEqualTo(
            genericLockAndFetch.createGenericSelectForUpdateQuery(
                "artwork_tasks", 4, CONDITION, true));
  }
}
