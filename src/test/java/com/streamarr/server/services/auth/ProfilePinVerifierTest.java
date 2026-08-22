package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ProfileFixture;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile Pin Verifier Tests")
class ProfilePinVerifierTest {

  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build(),
          new MutableClock());
  private final ProfilePinVerifier verifier = new ProfilePinVerifier(new TestEncoder(), throttle);
  private final UUID accountId = UUID.randomUUID();

  @Test
  @DisplayName("Should accept the PIN and reset the budget when the PIN is correct")
  void shouldAcceptPinAndResetBudgetWhenPinIsCorrect() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000"))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatCode(() -> verifier.verify(accountId, profile, "4242")).doesNotThrowAnyException();
    assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000"))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatCode(() -> verifier.verify(accountId, profile, "4242")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject the PIN when it is missing or blank")
  void shouldRejectPinWhenMissingOrBlank() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, null))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatThrownBy(() -> verifier.verify(accountId, profile, " "))
        .isInstanceOf(InvalidProfilePinException.class);
  }

  @Test
  @DisplayName("Should throttle before hashing when the Account and Profile budget is exhausted")
  void shouldThrottleBeforeHashingWhenAccountAndProfileBudgetIsExhausted() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();
    for (var attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000"))
          .isInstanceOf(InvalidProfilePinException.class);
    }

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    // Another Account guessing the same Profile has its own budget.
    assertThatCode(() -> verifier.verify(UUID.randomUUID(), profile, "4242"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should treat the stored hash as a wrong PIN when it is unreadable")
  void shouldTreatStoredHashAsWrongPinWhenUnreadable() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("unreadable").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242"))
        .isInstanceOf(InvalidProfilePinException.class);
  }

  @Test
  @DisplayName("Should not log stored PIN hash material when the hash is unreadable")
  void shouldNotLogStoredPinHashMaterialWhenHashIsUnreadable() {
    var storedHash = "unreadable:stored-secret-marker";
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash(storedHash).build();
    var logger = (Logger) LoggerFactory.getLogger(ProfilePinVerifier.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242"))
          .isInstanceOf(InvalidProfilePinException.class);

      assertThat(appender.list)
          .singleElement()
          .satisfies(
              event -> {
                var throwable = ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertThat(event.getFormattedMessage()).doesNotContain(storedHash);
                assertThat(throwable).doesNotContain(storedHash);
              });
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static final class TestEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return "pin:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      if (encodedPassword.startsWith("unreadable")) {
        throw new IllegalArgumentException(encodedPassword);
      }

      return encode(rawPassword).equals(encodedPassword);
    }
  }
}
