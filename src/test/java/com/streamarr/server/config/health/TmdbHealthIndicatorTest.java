package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeHttpClient;
import com.streamarr.server.fakes.MutableClock;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Tests")
class TmdbHealthIndicatorTest {

  private static final Duration PROBE_TIMEOUT = Duration.ofMillis(200);
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);

  private final AtomicReference<Instant> currentTime =
      new AtomicReference<>(Instant.parse("2026-08-03T12:00:00Z"));
  private final MutableClock clock = new MutableClock(currentTime);

  @Test
  @DisplayName("Should report UP when TMDB returns 200")
  void shouldReportUpWhenTmdbReturns200() {
    var indicator = indicatorFor(FakeHttpClient.respondingWith(200));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report UP when TMDB returns 401 (reachable but unauthorized)")
  void shouldReportUpWhenTmdbReturns401() {
    var indicator = indicatorFor(FakeHttpClient.respondingWith(401));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Should report DOWN when TMDB returns non-200/401 status")
  void shouldReportDownWhenTmdbReturnsUnexpectedStatus() {
    var indicator = indicatorFor(FakeHttpClient.respondingWith(503));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should report DOWN when IOException is thrown")
  void shouldReportDownWhenIOExceptionThrown() {
    var indicator = indicatorFor(FakeHttpClient.failingWith(new IOException("connection refused")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("Should report DOWN within the probe timeout when TMDB never responds")
  void shouldReportDownWithinProbeTimeoutWhenTmdbNeverResponds() {
    var indicator = indicatorFor(FakeHttpClient.unresponsive());

    var startedAt = System.nanoTime();
    var health = indicator.health();
    var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("Should call TMDB once when probed twice within the cache TTL")
  void shouldCallTmdbOnceWhenProbedTwiceWithinCacheTtl() {
    var client = FakeHttpClient.respondingWith(200);
    var indicator = indicatorFor(client);

    indicator.health();
    currentTime.set(currentTime.get().plus(CACHE_TTL.minusSeconds(1)));
    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(client.sendCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should call TMDB again when probed after the cache TTL expires")
  void shouldCallTmdbAgainWhenProbedAfterCacheTtlExpires() {
    var client = FakeHttpClient.respondingWith(200);
    var indicator = indicatorFor(client);

    indicator.health();
    currentTime.set(currentTime.get().plus(CACHE_TTL));
    indicator.health();

    assertThat(client.sendCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should re-interrupt thread when InterruptedException is thrown")
  void shouldReInterruptThreadWhenInterruptedExceptionThrown() {
    var indicator =
        indicatorFor(FakeHttpClient.failingWith(new InterruptedException("interrupted")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();

    // Clear the interrupt flag to avoid polluting other tests
    Thread.interrupted();
  }

  private TmdbHealthIndicator indicatorFor(HttpClient client) {
    return new TmdbHealthIndicator(
        client,
        TmdbHealthProperties.builder().probeTimeout(PROBE_TIMEOUT).cacheTtl(CACHE_TTL).build(),
        clock);
  }
}
