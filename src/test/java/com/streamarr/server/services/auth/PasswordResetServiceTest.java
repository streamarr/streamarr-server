package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redeeming a reset code over fakes: the winner changes the password and revokes every session, a
 * disabled Account may redeem, and no session is created — a reset never bypasses a disable.
 */
@Tag("UnitTest")
@DisplayName("Password Reset Redemption Service Tests")
class PasswordResetServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final OpaqueOneTimeCodes opaqueCodes = new OpaqueOneTimeCodes();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final PasswordResetService service =
      new PasswordResetService(
          resetCodes,
          accounts,
          sessions,
          opaqueCodes,
          new CredentialGuessThrottle(
              AuthThrottleProperties.builder()
                  .maxAttempts(5)
                  .window(Duration.ofMinutes(15))
                  .build(),
              clock),
          new PlainEncoder(),
          new TransactionTemplate(new FakeTransactionManager()),
          clock);

  private UserAccount account;

  @BeforeEach
  void setUp() {
    account = accounts.save(AccountFixture.defaultAccountBuilder().passwordHash("old").build());
  }

  @Test
  @DisplayName("Should change the password and revoke every session when a reset is redeemed")
  void shouldChangePasswordAndRevokeEverySessionWhenResetIsRedeemed() {
    var webSession =
        sessions.save(AuthSession.builder().accountId(account.getId()).deviceName("web").build());
    var televisionSession =
        sessions.save(
            AuthSession.builder().accountId(account.getId()).deviceName("television").build());
    var issued = pendingCode();

    service.redeem(issued.code(), "a brand new passphrase");

    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:a brand new passphrase");
    assertThat(sessions.findById(webSession.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(sessions.findById(televisionSession.getId()).orElseThrow().getRevokedAt())
        .isNotNull();
    assertThat(resetCodes.findAll().getFirst().getStatus())
        .isEqualTo(PasswordResetCodeStatus.REDEEMED);
    assertThat(sessions.findAll()).hasSize(2);
  }

  @Test
  @DisplayName("Should redeem when the Account is disabled")
  void shouldRedeemWhenAccountIsDisabled() {
    account.setEnabled(false);
    var issued = pendingCode();

    service.redeem(issued.code(), "a brand new passphrase");

    var after = accounts.findById(account.getId()).orElseThrow();
    assertThat(after.getPasswordHash()).isEqualTo("hashed:a brand new passphrase");
    assertThat(after.isEnabled()).isFalse();
  }

  @Test
  @DisplayName("Should let exactly one redemption win when a code is presented repeatedly")
  void shouldLetExactlyOneRedemptionWinWhenCodeIsPresentedRepeatedly() {
    var issued = pendingCode();
    service.redeem(issued.code(), "first passphrase");

    var consumed = issued.code();
    assertThatThrownBy(() -> service.redeem(consumed, "second passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:first passphrase");
  }

  @Test
  @DisplayName("Should answer like an unknown code when a reset code is expired")
  void shouldAnswerLikeUnknownCodeWhenResetCodeIsExpired() {
    var issued = pendingCode();
    resetCodes.findAll().getFirst().setExpiresAt(NOW.minusSeconds(1));

    var expiredCode = issued.code();
    assertThatThrownBy(() -> service.redeem(expiredCode, "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThatThrownBy(() -> service.redeem("unknown.secret", "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  private OpaqueOneTimeCodes.IssuedCode pendingCode() {
    var issued = opaqueCodes.issue();
    resetCodes.save(
        PasswordResetCode.builder()
            .accountId(account.getId())
            .issuerAccountId(account.getId())
            .expiresAt(NOW.plus(Duration.ofHours(1)))
            .publicId(issued.publicId())
            .secretDigest(issued.digest())
            .build());
    return issued;
  }

  private static final class PlainEncoder implements PasswordEncoder {
    @Override
    public String encode(CharSequence rawPassword) {
      return "hashed:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encodedPassword.equals(encode(rawPassword));
    }
  }
}
