package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.InvalidProfilePinException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
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

  private static final String IP_ADDRESS = "192.0.2.21";

  private final MutableClock clock = new MutableClock();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final ProfilePinVerifier verifier =
      new ProfilePinVerifier(new TestEncoder(), credentialAttempts.gate(clock));
  private final UUID accountId = UUID.randomUUID();

  @Test
  @DisplayName("Should accept the PIN and reset the failure sequence when the PIN is correct")
  void shouldAcceptPinAndResetFailureSequenceWhenPinIsCorrect() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000", IP_ADDRESS))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatCode(() -> verifier.verify(accountId, profile, "4242", IP_ADDRESS))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000", IP_ADDRESS))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatCode(() -> verifier.verify(accountId, profile, "4242", IP_ADDRESS))
        .doesNotThrowAnyException();

    assertThat(credentialAttempts.attempts().getFirst().target().kind())
        .isEqualTo(CredentialKind.PROFILE_PIN);
    assertThat(credentialAttempts.attempts().getFirst().target().profileId())
        .isEqualTo(profile.getId());
    assertThat(credentialAttempts.attempts().getFirst().target().ipAddress()).isEqualTo(IP_ADDRESS);
    assertThat(credentialAttempts.attempts().getFirst().result())
        .isEqualTo(CredentialAttemptResult.FAILED);
  }

  @Test
  @DisplayName("Should reject the PIN when it is missing or blank")
  void shouldRejectPinWhenMissingOrBlank() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, null, IP_ADDRESS))
        .isInstanceOf(InvalidProfilePinException.class);
    assertThatThrownBy(() -> verifier.verify(accountId, profile, " ", IP_ADDRESS))
        .isInstanceOf(InvalidProfilePinException.class);
  }

  @Test
  @DisplayName("Should throttle before hashing when the Account and Profile limit is exhausted")
  void shouldThrottleBeforeHashingWhenAccountAndProfileLimitIsExhausted() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("pin:4242").build();
    for (var attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> verifier.verify(accountId, profile, "0000", IP_ADDRESS))
          .isInstanceOf(InvalidProfilePinException.class);
    }
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242", IP_ADDRESS))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    // Another Account guessing the same Profile has its own target.
    credentialAttempts.allowReservations();
    assertThatCode(() -> verifier.verify(UUID.randomUUID(), profile, "4242", IP_ADDRESS))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should treat the stored hash as a wrong PIN when it is unreadable")
  void shouldTreatStoredHashAsWrongPinWhenUnreadable() {
    var profile =
        ProfileFixture.defaultProfileBuilder().id(UUID.randomUUID()).pinHash("unreadable").build();

    assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242", IP_ADDRESS))
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
      assertThatThrownBy(() -> verifier.verify(accountId, profile, "4242", IP_ADDRESS))
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
