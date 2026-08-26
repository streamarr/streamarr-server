package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@Tag("IntegrationTest")
@DisplayName("Credential Attempt Retention Sweeper Integration Tests")
class CredentialAttemptRetentionSweeperIT extends AbstractIntegrationTest {

  @Autowired private ScheduledTaskHolder scheduledTasks;

  @Test
  @DisplayName("Should schedule the sweep soon after startup and daily when the application starts")
  void shouldScheduleSweepSoonAfterStartupAndDailyWhenApplicationStarts() {
    var sweep =
        scheduledTasks.getScheduledTasks().stream()
            .map(ScheduledTask::getTask)
            .filter(FixedDelayTask.class::isInstance)
            .map(FixedDelayTask.class::cast)
            .filter(CredentialAttemptRetentionSweeperIT::runsTheSweeper)
            .findFirst()
            .orElseThrow();

    // An instance restarted more often than the initial delay would otherwise never sweep.
    assertThat(sweep.getInitialDelayDuration()).isLessThanOrEqualTo(Duration.ofMinutes(5));
    assertThat(sweep.getIntervalDuration()).isEqualTo(Duration.ofDays(1));
  }

  /** Spring wraps the scheduled method; the wrapper's text is the method's qualified name. */
  private static boolean runsTheSweeper(FixedDelayTask task) {
    return task.getRunnable()
        .toString()
        .equals(CredentialAttemptRetentionSweeper.class.getName() + ".deleteExpiredAttempts");
  }
}
