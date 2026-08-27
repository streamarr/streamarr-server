package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The step-up ceremony at its entry: the device gate, then the shared password checkpoint. */
@Tag("UnitTest")
@DisplayName("Reauthentication Service Tests")
class ReauthenticationServiceTest {

  private static final String PASSWORD = "correct horse battery staple";
  private static final String IP_ADDRESS = "192.0.2.23";

  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final PasswordEncoder encoder = new PlainEncoder();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final ReauthenticationService service =
      new ReauthenticationService(
          accounts,
          sessions,
          new AccountPasswordVerifier(
              encoder,
              new PasswordTimingEqualizer(encoder),
              credentialAttempts.gate(
                  Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC))));

  private UserAccount account;
  private AuthSession session;

  @BeforeEach
  void setUp() {
    account =
        accounts.save(
            AccountFixture.defaultAccountBuilder().passwordHash(encoder.encode(PASSWORD)).build());
    session =
        sessions.save(AuthSession.builder().accountId(account.getId()).deviceName("web").build());
  }

  @Test
  @DisplayName("Should reject before password work when the session is device-bound")
  void shouldRejectBeforePasswordWorkWhenSessionDeviceBound() {
    var device =
        AuthenticatedIdentityFixture.accountScopedBuilder()
            .accountId(account.getId())
            .authSessionId(session.getId())
            .registrationId(UUID.randomUUID())
            .build();
    var command = command(PASSWORD);

    assertThatThrownBy(() -> service.reauthenticate(device, command))
        .isInstanceOf(DeviceBoundSessionException.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse reauthentication when the journal blocks the attempt")
  void shouldRefuseReauthenticationWhenJournalBlocksAttempt() {
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));
    var identity = identity();
    var command = command(PASSWORD);

    assertThatThrownBy(() -> service.reauthenticate(identity, command))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should journal a failure against the Account when the password is wrong")
  void shouldJournalFailureAgainstAccountWhenPasswordIsWrong() {
    var identity = identity();
    var command = command("not the password");

    assertThatThrownBy(() -> service.reauthenticate(identity, command))
        .isInstanceOf(InvalidCredentialsException.class);

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target()).isEqualTo(passwordTarget());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });
  }

  @Test
  @DisplayName("Should journal a success against the Account when the password is correct")
  void shouldJournalSuccessAgainstAccountWhenPasswordIsCorrect() {
    var context = service.reauthenticate(identity(), command(PASSWORD));

    assertThat(context.account().getId()).isEqualTo(account.getId());
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target()).isEqualTo(passwordTarget());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should refuse the correct password when five wrong passwords precede it")
  void shouldRefuseCorrectPasswordWhenFiveWrongPasswordsPrecedeIt() {
    var identity = identity();
    for (var attempt = 0; attempt < 5; attempt++) {
      var wrong = command("wrong-" + attempt);
      assertThatThrownBy(() -> service.reauthenticate(identity, wrong))
          .isInstanceOf(InvalidCredentialsException.class);
    }

    var correct = command(PASSWORD);

    assertThatThrownBy(() -> service.reauthenticate(identity, correct))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  private CredentialAttemptTarget passwordTarget() {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
        .accountId(account.getId())
        .ipAddress(IP_ADDRESS)
        .build();
  }

  private static ReauthenticationCommand command(String password) {
    return ReauthenticationCommand.builder().password(password).ipAddress(IP_ADDRESS).build();
  }

  private AuthenticatedIdentity identity() {
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .accountId(account.getId())
        .authSessionId(session.getId())
        .build();
  }

  private static final class PlainEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return "plain:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encode(rawPassword).equals(encodedPassword);
    }
  }
}
