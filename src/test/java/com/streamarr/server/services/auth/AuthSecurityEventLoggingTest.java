package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.exceptions.TokenReuseDetectedException;
import com.streamarr.server.exceptions.TooManyLoginAttemptsException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Authentication Security Event Logging Tests")
class AuthSecurityEventLoggingTest {

  @Test
  @DisplayName("Should log warning when refresh token reuse revokes session")
  void shouldLogWarningWhenRefreshTokenReuseRevokesSession() {
    var currentTime = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    var clock = new MutableClock(currentTime);
    var service =
        new RefreshTokenService(
            new FakeAuthSessionRepository(),
            new FakeRefreshTokenRepository(),
            AuthTokenProperties.builder()
                .signingKey("")
                .accessTokenTtl(Duration.ofMinutes(10))
                .refreshTokenTtl(Duration.ofDays(30))
                .rotationGrace(Duration.ofSeconds(30))
                .build(),
            clock,
            new CapturingEventPublisher());
    var account = AccountFixture.defaultAccountBuilder().build();
    var issued = service.createSession(account, "security-log-test");
    service.redeem(issued.rawToken());
    currentTime.updateAndGet(instant -> instant.plusSeconds(31));

    try (var logs = LogCapture.forClass(RefreshTokenService.class)) {
      assertThatThrownBy(() -> service.redeem(issued.rawToken()))
          .isInstanceOf(TokenReuseDetectedException.class);

      assertThat(logs.events()).anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }
  }

  @Test
  @DisplayName("Should log warning when login attempt is throttled")
  void shouldLogWarningWhenLoginAttemptIsThrottled() {
    var currentTime = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    var throttle =
        new LoginThrottle(
            AuthThrottleProperties.builder().maxAttempts(1).window(Duration.ofMinutes(15)).build(),
            new MutableClock(currentTime));
    throttle.registerAttempt("account@example.com", "127.0.0.1");

    try (var logs = LogCapture.forClass(LoginThrottle.class)) {
      assertThatThrownBy(() -> throttle.registerAttempt("account@example.com", "127.0.0.1"))
          .isInstanceOf(TooManyLoginAttemptsException.class);

      assertThat(logs.events()).anyMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }
  }

  private record LogCapture(Logger logger, ListAppender<ILoggingEvent> appender)
      implements AutoCloseable {

    private static LogCapture forClass(Class<?> type) {
      var logger = (Logger) LoggerFactory.getLogger(type);
      var appender = new ListAppender<ILoggingEvent>();
      appender.start();
      logger.addAppender(appender);
      return new LogCapture(logger, appender);
    }

    private java.util.List<ILoggingEvent> events() {
      return appender.list;
    }

    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
