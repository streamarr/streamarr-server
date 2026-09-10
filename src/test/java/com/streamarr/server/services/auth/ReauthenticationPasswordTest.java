package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Reauthentication Password Tests")
class ReauthenticationPasswordTest {
  private static final String PASSWORD = "correct horse battery staple";
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeCredentialAttemptRepository attempts = new FakeCredentialAttemptRepository();
  private final CompletedPasswordWork encoder = new CompletedPasswordWork();
  private final ReauthenticationService service =
      new ReauthenticationService(
          accounts,
          sessions,
          new AccountPasswordVerifier(
              encoder, new PasswordTimingEqualizer(encoder), attempts.gate(new MutableClock())));

  private final ReauthenticationCommand correctCommand = command(PASSWORD);
  private final ReauthenticationCommand wrongCommand = command("wrong");

  @ParameterizedTest(name = "stored hash: {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "unreadable",
        "{argon2id}not-an-argon-hash",
        "{bcrypt}not-a-bcrypt-hash",
        "{argon2id}",
        "{bcrypt}"
      })
  @DisplayName("Should refuse after completed password work when the stored hash is unreadable")
  void shouldRefuseAfterCompletedPasswordWorkWhenStoredHashIsUnreadable(String hash) {
    var identity = accountWithHash(hash);
    assertThatThrownBy(() -> service.reauthenticate(identity, correctCommand))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(encoder.completedWork).containsExactly(false);
    assertFailure(identity);
  }

  @Test
  @DisplayName("Should refuse after completed password work when the Account is disabled")
  void shouldRefuseAfterCompletedPasswordWorkWhenAccountIsDisabled() {
    var identity = accountWithHash(encoder.encode(PASSWORD));
    var account = accounts.findById(identity.accountId()).orElseThrow();
    account.setEnabled(false);
    accounts.save(account);
    assertThatThrownBy(() -> service.reauthenticate(identity, correctCommand))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(encoder.completedWork).containsExactly(false);
    assertFailure(identity);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should complete one password comparison when the Account has a readable hash")
  void shouldCompleteOnePasswordComparisonWhenAccountHasReadableHash(boolean correct) {
    var identity = accountWithHash(encoder.encode(PASSWORD));
    if (correct) {
      assertThat(service.reauthenticate(identity, correctCommand).account().getId())
          .isEqualTo(identity.accountId());
    } else {
      assertThatThrownBy(() -> service.reauthenticate(identity, wrongCommand))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThat(encoder.completedWork).containsExactly(correct);
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt ->
                assertThat(attempt.result())
                    .isEqualTo(
                        correct
                            ? CredentialAttemptResult.SUCCEEDED
                            : CredentialAttemptResult.FAILED));
  }

  @Test
  @DisplayName("Should skip password work when the journal blocks reauthentication")
  void shouldSkipPasswordWorkWhenJournalBlocksReauthentication() {
    var identity = accountWithHash(encoder.encode(PASSWORD));
    attempts.rejectReservations(Duration.ofMinutes(15));
    encoder.forbidden = true;
    assertThatThrownBy(() -> service.reauthenticate(identity, correctCommand))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(attempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should keep another Account usable when one Account exhausts its password budget")
  void shouldKeepAnotherAccountUsableWhenOneAccountExhaustsPasswordBudget() {
    var blocked = accountWithHash(encoder.encode(PASSWORD));
    var other = accountWithHash(encoder.encode(PASSWORD));
    for (var i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.reauthenticate(blocked, wrongCommand))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    assertThat(service.reauthenticate(other, correctCommand).account().getId())
        .isEqualTo(other.accountId());
    assertThatThrownBy(() -> service.reauthenticate(blocked, correctCommand))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should retain each journal outcome when passwords alternate")
  void shouldRetainEachJournalOutcomeWhenPasswordsAlternate() {
    var identity = accountWithHash(encoder.encode(PASSWORD));
    assertThatThrownBy(() -> service.reauthenticate(identity, wrongCommand))
        .isInstanceOf(InvalidCredentialsException.class);
    service.reauthenticate(identity, correctCommand);
    assertThatThrownBy(() -> service.reauthenticate(identity, wrongCommand))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(attempts.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .containsExactly(
            CredentialAttemptResult.FAILED,
            CredentialAttemptResult.SUCCEEDED,
            CredentialAttemptResult.FAILED);
  }

  private AuthenticatedIdentity accountWithHash(String hash) {
    var account = accounts.save(AccountFixture.defaultAccountBuilder().passwordHash(hash).build());
    var session = sessions.save(AuthSession.builder().accountId(account.getId()).build());
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .build();
  }

  private ReauthenticationCommand command(String password) {
    return ReauthenticationCommand.builder().password(password).ipAddress("192.0.2.20").build();
  }

  private void assertFailure(AuthenticatedIdentity identity) {
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
              assertThat(attempt.metadata().accountId()).isEqualTo(identity.accountId());
              assertThat(attempt.metadata().kind())
                  .isEqualTo(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION);
            });
  }

  private static final class CompletedPasswordWork implements PasswordEncoder {
    private final PasswordEncoder delegate =
        new PasswordEncoderConfig()
            .passwordEncoder(
                Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build());
    private final List<Boolean> completedWork = new ArrayList<>();
    private boolean forbidden;

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      if (forbidden) {
        throw new AssertionError("Blocked reauthentication reached password comparison");
      }

      var matched = delegate.matches(rawPassword, encodedPassword);
      // Only a parsed Argon2 hash reaches the expensive operation in these fixtures.
      if (encodedPassword.startsWith("{argon2id}$argon2id$")) {
        completedWork.add(matched);
      }

      return matched;
    }
  }
}
