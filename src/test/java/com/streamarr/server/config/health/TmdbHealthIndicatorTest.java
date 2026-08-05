package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.fakes.FakeHttpClient;
import com.streamarr.server.fakes.MutableClock;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

@Tag("UnitTest")
@DisplayName("TMDB Health Indicator Tests")
class TmdbHealthIndicatorTest {

  private static final Duration PROBE_TIMEOUT = Duration.ofMillis(200);
  private static final Duration CACHE_TTL = Duration.ofSeconds(30);
  private static final String DEGRADED_DESCRIPTION = "TMDB metadata service is unavailable";

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
  @DisplayName("Should report DEGRADED when TMDB returns non-200/401 status")
  void shouldReportDegradedWhenTmdbReturnsUnexpectedStatus() {
    var indicator = indicatorFor(FakeHttpClient.respondingWith(503));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
    assertThat(health.getStatus().getDescription()).isEqualTo(DEGRADED_DESCRIPTION);
  }

  @Test
  @DisplayName("Should log warning when TMDB returns unexpected status")
  void shouldLogWarningWhenTmdbReturnsUnexpectedStatus() {
    var logger = (Logger) LoggerFactory.getLogger(TmdbHealthIndicator.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      indicatorFor(FakeHttpClient.respondingWith(503)).health();

      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).contains("503");
              });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  @DisplayName("Should report DEGRADED when IOException is thrown")
  void shouldReportDegradedWhenIOExceptionThrown() {
    var indicator = indicatorFor(FakeHttpClient.failingWith(new IOException("connection refused")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
    assertThat(health.getStatus().getDescription()).isEqualTo(DEGRADED_DESCRIPTION);
  }

  @Test
  @DisplayName("Should report DEGRADED when health probe times out")
  void shouldReportDegradedWhenHealthProbeTimesOut() {
    var indicator =
        indicatorFor(FakeHttpClient.failingWith(new HttpTimeoutException("request timed out")));

    var health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
  }

  @Test
  @DisplayName("Should apply probe timeout when health queried")
  void shouldApplyProbeTimeoutWhenHealthQueried() {
    var client = FakeHttpClient.respondingWith(200);
    var indicator = indicatorFor(client);

    indicator.health();

    assertThat(client.sentRequests())
        .singleElement()
        .satisfies(request -> assertThat(request.timeout()).contains(PROBE_TIMEOUT));
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
  @DisplayName("Should cache DEGRADED verdict when health probe times out")
  void shouldCacheDegradedVerdictWhenHealthProbeTimesOut() {
    var client = FakeHttpClient.failingWith(new HttpTimeoutException("request timed out"));
    var indicator = indicatorFor(client);

    var firstHealth = indicator.health();
    var secondHealth = indicator.health();

    assertThat(firstHealth.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
    assertThat(secondHealth.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
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
  @DisplayName("Should start cache TTL when slow probe completes")
  void shouldStartCacheTtlWhenSlowProbeCompletes() throws Exception {
    var responses = FakeHttpClient.respondingWithBlockedFirst(200, 200);
    var client = responses.client();
    var indicator = indicatorFor(client);
    Callable<Health> healthProbe = indicator::health;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var probe = executor.submit(healthProbe);
      try {
        assertThat(responses.awaitFirstRequest(Duration.ofSeconds(5))).isTrue();
        currentTime.updateAndGet(instant -> instant.plus(CACHE_TTL));
        responses.releaseFirstResponse();
        assertThat(probe.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
      } finally {
        responses.releaseFirstResponse();
      }
    }

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(client.sendCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should preserve published verdict when older probe completes last")
  void shouldPreservePublishedVerdictWhenOlderProbeCompletesLast() throws Exception {
    var responses = FakeHttpClient.respondingWithBlockedFirst(503, 200);
    var client = responses.client();
    var indicator = indicatorFor(client);
    Callable<Health> healthProbe = indicator::health;

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var olderProbe = executor.submit(healthProbe);
      try {
        assertThat(responses.awaitFirstRequest(Duration.ofSeconds(5))).isTrue();

        var newerProbe = executor.submit(healthProbe);
        assertThat(newerProbe.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(Status.UP);
        responses.releaseFirstResponse();
        assertThat(olderProbe.get(5, TimeUnit.SECONDS).getStatus())
            .isEqualTo(TmdbHealthIndicator.DEGRADED);
      } finally {
        responses.releaseFirstResponse();
      }
    }

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(client.sendCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should re-interrupt thread when InterruptedException is thrown")
  void shouldReInterruptThreadWhenInterruptedExceptionThrown() {
    var indicator =
        indicatorFor(FakeHttpClient.failingWith(new InterruptedException("interrupted")));

    try {
      var health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(TmdbHealthIndicator.DEGRADED);
      assertThat(health.getStatus().getDescription()).isEqualTo(DEGRADED_DESCRIPTION);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should not cache degraded verdict when probe is interrupted")
  void shouldNotCacheDegradedVerdictWhenProbeIsInterrupted() {
    var client = FakeHttpClient.failingWith(new InterruptedException("interrupted"));
    var indicator = indicatorFor(client);

    try {
      indicator.health();
      Thread.interrupted();

      indicator.health();

      assertThat(client.sendCount()).isEqualTo(2);
    } finally {
      Thread.interrupted();
    }
  }

  private TmdbHealthIndicator indicatorFor(HttpClient client) {
    return new TmdbHealthIndicator(
        client,
        TmdbHealthProperties.builder().probeTimeout(PROBE_TIMEOUT).cacheTtl(CACHE_TTL).build(),
        clock,
        "https://api.themoviedb.org/3");
  }
}
