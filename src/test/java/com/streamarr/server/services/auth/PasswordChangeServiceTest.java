package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeRefreshTokenRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
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
  private final CapturingEventPublisher eventPublisher = new CapturingEventPublisher();
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
              new TokenReuseRevocationWriter(sessionRepository, tokenRepository, eventPublisher)),
          eventPublisher);
  private final PasswordChangeService service =
      new PasswordChangeService(
          accountRepository,
          sessionRepository,
          tokenRepository,
          refreshTokenService,
          passwordEncoder,
          clock,
          eventPublisher);

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
