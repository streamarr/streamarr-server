package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeHttpClient;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Tests")
class TmdbHealthIndicatorTest {

  @Test
  @DisplayName("Should report UP when TMDB returns 200")
  void shouldReportUpWhenTmdbReturns200() {
    var indicator = new TmdbHealthIndicator(FakeHttpClient.respondingWith(200));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report UP when TMDB returns 401 (reachable but unauthorized)")
  void shouldReportUpWhenTmdbReturns401() {
    var indicator = new TmdbHealthIndicator(FakeHttpClient.respondingWith(401));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report DOWN when TMDB returns non-200/401 status")
  void shouldReportDownWhenTmdbReturnsUnexpectedStatus() {
    var indicator = new TmdbHealthIndicator(FakeHttpClient.respondingWith(503));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should report DOWN when IOException is thrown")
  void shouldReportDownWhenIOExceptionThrown() {
    var indicator =
        new TmdbHealthIndicator(FakeHttpClient.failingWith(new IOException("connection refused")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should re-interrupt thread when InterruptedException is thrown")
  void shouldReInterruptThreadWhenInterruptedExceptionThrown() {
    var indicator =
        new TmdbHealthIndicator(
            FakeHttpClient.failingWith(new InterruptedException("interrupted")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();

    // Clear the interrupt flag to avoid polluting other tests
    Thread.interrupted();
  }
}
