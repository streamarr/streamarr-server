package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@Tag("UnitTest")
@DisplayName("Credential Retention Scheduling Tests")
class CredentialRetentionSchedulingTest {

  @Test
  @DisplayName("Should delete expired attempts when the registered daily task runs")
  void shouldDeleteExpiredAttemptsWhenRegisteredDailyTaskRuns() {
    var now = Instant.parse("2026-08-26T12:00:00Z");
    var repository = new FakeCredentialAttemptRepository();
    var target =
        CredentialAttemptTarget.builder()
            .kind(CredentialKind.ACCOUNT_LOGIN)
            .ipAddress("192.0.2.30")
            .build();
    repository.gate(Clock.fixed(now.minus(Duration.ofDays(31)), ZoneOffset.UTC)).reserve(target);
    var retained = repository.gate(Clock.fixed(now, ZoneOffset.UTC)).reserve(target);

    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(SchedulingConfiguration.class);
      context.registerBean(
          CredentialAttemptRetentionSweeper.class,
          () ->
              new CredentialAttemptRetentionSweeper(repository, Clock.fixed(now, ZoneOffset.UTC)));
      context.refresh();

      var tasks = context.getBean(ScheduledTaskHolder.class).getScheduledTasks();
      assertThat(tasks).hasSize(1);
      var task = tasks.iterator().next().getTask();
      assertThat(task).isInstanceOf(FixedDelayTask.class);
      var daily = (FixedDelayTask) task;
      assertThat(daily.getInitialDelayDuration()).isLessThanOrEqualTo(Duration.ofMinutes(5));
      assertThat(daily.getIntervalDuration()).isEqualTo(Duration.ofDays(1));
      daily.getRunnable().run();

      assertThat(repository.attempts())
          .singleElement()
          .satisfies(attempt -> assertThat(attempt.id()).isEqualTo(retained.id()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingConfiguration {}
}
