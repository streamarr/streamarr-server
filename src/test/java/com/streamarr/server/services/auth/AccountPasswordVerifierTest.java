package com.streamarr.server.services.auth;

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

  private static final class TestPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return rawPassword.toString();
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return rawPassword.toString().equals(encodedPassword);
    }
  }
}
