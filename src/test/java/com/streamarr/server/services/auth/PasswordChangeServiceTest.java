package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Password Change Service Tests")
class PasswordChangeServiceTest {

  private final FakeUserAccountRepository accountRepository = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessionRepository = new FakeAuthSessionRepository();
  private final FakeRefreshTokenRepository tokenRepository = new FakeRefreshTokenRepository();
  private final PasswordEncoder passwordEncoder = new TestPasswordEncoder();
  private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final RefreshTokenService refreshTokenService =
      new RefreshTokenService(
          sessionRepository,
          tokenRepository,
          AuthTokenProperties.builder()
              .refreshTokenTtl(Duration.ofDays(30))
              .rotationGrace(Duration.ofSeconds(30))
              .build(),
          clock,
          new TokenReuseRevoker(
              new TokenReuseRevocationWriter(sessionRepository, tokenRepository)));
  private final PasswordChangeCompletionService completionService =
      new PasswordChangeCompletionService(
          accountRepository, sessionRepository, tokenRepository, refreshTokenService, clock);
  private final PasswordChangeService service =
      new PasswordChangeService(
          accountRepository,
          completionService,
          new AccountPasswordVerifier(
              passwordEncoder,
              new PasswordTimingEqualizer(passwordEncoder),
              credentialAttempts.gate(clock)),
          passwordEncoder);

  @Test
  @DisplayName("Should reject a password change when the identity is device-bound")
  void shouldRejectPasswordChangeWhenIdentityIsDeviceBound() {
    var identity =
        AuthenticatedIdentityFixture.accountScopedBuilder()
            .registrationId(UUID.randomUUID())
            .build();
    var command = commandBuilder().build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(DeviceBoundSessionException.class);
  }

  @Test
  @DisplayName("Should fail closed without issuing a token when account is missing")
  void shouldFailClosedWithoutIssuingTokenWhenAccountMissing() {
    var identity = identity(UUID.randomUUID(), UUID.randomUUID());
    var command = commandBuilder().build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed without issuing a token when caller session is revoked")
  void shouldFailClosedWithoutIssuingTokenWhenCallerSessionRevoked() {
    var currentPassword = UUID.randomUUID().toString();
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(currentPassword))
                .build());
    var caller =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(account.getId())
                .deviceName("revoked-caller")
                .revokedAt(clock.instant())
                .revokedReason(SessionRevocationReason.LOGOUT)
                .build());
    var identity = identity(account.getId(), caller.getId());
    var command = commandBuilder().currentPassword(currentPassword).build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a password change when the caller session belongs to another account")
  void shouldRejectPasswordChangeWhenCallerSessionBelongsToAnotherAccount() {
    var currentPassword = UUID.randomUUID().toString();
    var originalPasswordHash = passwordEncoder.encode(currentPassword);
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder().passwordHash(originalPasswordHash).build());
    var otherAccount = accountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var otherAccountSession =
        sessionRepository.save(
            AuthSession.builder()
                .accountId(otherAccount.getId())
                .deviceName("another-account")
                .build());
    var identity = identity(account.getId(), otherAccountSession.getId());
    var command = commandBuilder().currentPassword(currentPassword).build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(accountRepository.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(originalPasswordHash);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a password change when the account is disabled")
  void shouldRejectPasswordChangeWhenAccountDisabled() {
    var currentPassword = UUID.randomUUID().toString();
    var originalPasswordHash = passwordEncoder.encode(currentPassword);
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(originalPasswordHash)
                .enabled(false)
                .build());
    var caller =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("caller").build());
    var identity = identity(account.getId(), caller.getId());
    var command = commandBuilder().currentPassword(currentPassword).build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(accountRepository.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(originalPasswordHash);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the password change when the journal blocks the attempt")
  void shouldRefusePasswordChangeWhenJournalBlocksAttempt() {
    var currentPassword = UUID.randomUUID().toString();
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(currentPassword))
                .build());
    var caller =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("caller").build());
    var identity = identity(account.getId(), caller.getId());
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    var correctCommand = commandBuilder().currentPassword(currentPassword).build();

    assertThatThrownBy(() -> service.changePassword(identity, correctCommand))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  private ChangePasswordCommand.ChangePasswordCommandBuilder commandBuilder() {
    return ChangePasswordCommand.builder()
        .currentPassword(UUID.randomUUID().toString())
        .newPassword(UUID.randomUUID().toString())
        .ipAddress("192.0.2.22");
  }

  private AuthenticatedIdentity identity(UUID accountId, UUID sessionId) {
    return AuthenticatedIdentityFixture.accountScopedBuilder()
        .accountId(accountId)
        .authSessionId(sessionId)
        .build();
  }

  private static final class TestPasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encode(rawPassword).equals(encodedPassword);
    }
  }
}
