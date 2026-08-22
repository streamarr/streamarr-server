package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.DeviceBoundSessionException;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
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
              new CredentialGuessThrottle(
                  AuthThrottleProperties.builder()
                      .maxAttempts(2)
                      .window(Duration.ofMinutes(15))
                      .build(),
                  clock)),
          passwordEncoder);

  @Test
  @DisplayName("Should reject a password change when the identity is device-bound")
  void shouldRejectPasswordChangeWhenIdentityIsDeviceBound() {
    var identity =
        AuthenticatedIdentityFixture.accountScopedBuilder().registrationId(UUID.randomUUID()).build();
    var command =
        commandBuilder().accountId(identity.accountId()).sessionId(identity.authSessionId()).build();

    assertThatThrownBy(() -> service.changePassword(identity, command))
        .isInstanceOf(DeviceBoundSessionException.class);
  }

  @Test
  @DisplayName("Should fail closed without issuing a token when account is missing")
  void shouldFailClosedWithoutIssuingTokenWhenAccountMissing() {
    var command =
        commandBuilder().accountId(UUID.randomUUID()).sessionId(UUID.randomUUID()).build();

    assertThatThrownBy(() -> service.changePassword(command))
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
    var command =
        commandBuilder()
            .accountId(account.getId())
            .sessionId(caller.getId())
            .currentPassword(currentPassword)
            .build();

    assertThatThrownBy(() -> service.changePassword(command))
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
    var command =
        commandBuilder()
            .accountId(account.getId())
            .sessionId(otherAccountSession.getId())
            .currentPassword(currentPassword)
            .build();

    assertThatThrownBy(() -> service.changePassword(command))
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
    var command =
        commandBuilder()
            .accountId(account.getId())
            .sessionId(caller.getId())
            .currentPassword(currentPassword)
            .build();

    assertThatThrownBy(() -> service.changePassword(command))
        .isInstanceOf(InvalidCredentialsException.class);
    assertThat(accountRepository.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo(originalPasswordHash);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should throttle password changes when current password repeatedly wrong")
  void shouldThrottlePasswordChangesWhenCurrentPasswordRepeatedlyWrong() {
    var currentPassword = UUID.randomUUID().toString();
    var account =
        accountRepository.save(
            AccountFixture.defaultAccountBuilder()
                .passwordHash(passwordEncoder.encode(currentPassword))
                .build());
    var caller =
        sessionRepository.save(
            AuthSession.builder().accountId(account.getId()).deviceName("caller").build());
    var wrongCommand =
        commandBuilder().accountId(account.getId()).sessionId(caller.getId()).build();
    for (var attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> service.changePassword(wrongCommand))
          .isInstanceOf(InvalidCredentialsException.class);
    }
    var correctCommand =
        commandBuilder()
            .accountId(account.getId())
            .sessionId(caller.getId())
            .currentPassword(currentPassword)
            .build();

    assertThatThrownBy(() -> service.changePassword(correctCommand))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(tokenRepository.findAll()).isEmpty();
  }

  private ChangePasswordCommand.ChangePasswordCommandBuilder commandBuilder() {
    return ChangePasswordCommand.builder()
        .currentPassword(UUID.randomUUID().toString())
        .newPassword(UUID.randomUUID().toString());
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
