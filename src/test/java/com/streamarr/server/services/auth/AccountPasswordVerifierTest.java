package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Account Password Verifier Tests")
class AccountPasswordVerifierTest {

  private final CredentialGuessThrottle throttle =
      new CredentialGuessThrottle(
          AuthThrottleProperties.builder().maxAttempts(2).window(Duration.ofMinutes(15)).build(),
          Clock.systemUTC());
  private final AccountPasswordVerifier verifier =
      new AccountPasswordVerifier(new TestPasswordEncoder(), throttle);

  @Test
  @DisplayName("Should throttle Account password verification before hashing")
  void shouldThrottleAccountPasswordVerificationBeforeHashing() {
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(UUID.randomUUID())
            .passwordHash("correct password")
            .build();

    for (var attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> verifier.verify(account, "wrong password"))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThatThrownBy(() -> verifier.verify(account, "correct password"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should reset Account password attempts after successful verification")
  void shouldResetAccountPasswordAttemptsAfterSuccessfulVerification() {
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(UUID.randomUUID())
            .passwordHash("correct password")
            .build();

    assertThatThrownBy(() -> verifier.verify(account, "wrong password"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatCode(() -> verifier.verify(account, "correct password")).doesNotThrowAnyException();
    assertThatThrownBy(() -> verifier.verify(account, "wrong password"))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThatCode(() -> verifier.verify(account, "correct password")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject an unreadable stored Account password hash")
  void shouldRejectUnreadableStoredAccountPasswordHash() {
    var account =
        AccountFixture.defaultAccountBuilder()
            .id(UUID.randomUUID())
            .passwordHash("unreadable")
            .build();

    assertThatThrownBy(() -> verifier.verify(account, "password"))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  private static final class TestPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return rawPassword.toString();
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      if (encodedPassword.equals("unreadable")) {
        throw new IllegalArgumentException("Unreadable test hash");
      }
      return rawPassword.toString().equals(encodedPassword);
    }
  }
}
