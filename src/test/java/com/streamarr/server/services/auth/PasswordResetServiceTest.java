package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fakes.PlainPasswordEncoder;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redeeming a reset code over fakes: the winner changes the password and revokes every session, a
 * disabled Account may redeem, and no session is created — a reset never bypasses a disable.
 */
@Tag("UnitTest")
@DisplayName("Password Reset Service Tests")
class PasswordResetServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final OpaqueOneTimeCodes opaqueCodes = new OpaqueOneTimeCodes();
  private final MutableClock clock = new MutableClock(new AtomicReference<>(NOW));
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();

  private final PasswordResetService service = serviceUsing(accounts);

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

    redeem(issued.code(), "a brand new passphrase");

    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:a brand new passphrase");
    assertThat(sessions.findById(webSession.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(sessions.findById(televisionSession.getId()).orElseThrow().getRevokedAt())
        .isNotNull();
    assertThat(resetCodes.findAll().getFirst().getStatus())
        .isEqualTo(PasswordResetCodeStatus.REDEEMED);
    assertThat(sessions.findAll()).hasSize(2);
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target())
                  .isEqualTo(
                      CredentialAttemptTarget.builder()
                          .kind(CredentialKind.PASSWORD_RESET_CODE)
                          .credentialId(resetCodes.findAll().getFirst().getId())
                          .ipAddress("192.0.2.26")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should fail loudly when the locked Account rejects the password write")
  void shouldFailLoudlyWhenLockedAccountRejectsPasswordWrite() {
    var serviceRefusingPasswordWrite =
        serviceUsing(new PasswordWriteRefusingUserAccountRepository());
    var code = pendingCode().code();

    assertThatThrownBy(() -> redeem(serviceRefusingPasswordWrite, code, "new password"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(account.getId().toString());
  }

  @Test
  @DisplayName("Should redeem when the Account is disabled")
  void shouldRedeemWhenAccountIsDisabled() {
    account.setEnabled(false);
    var issued = pendingCode();

    redeem(issued.code(), "a brand new passphrase");

    var after = accounts.findById(account.getId()).orElseThrow();
    assertThat(after.getPasswordHash()).isEqualTo("hashed:a brand new passphrase");
    assertThat(after.isEnabled()).isFalse();
  }

  @Test
  @DisplayName("Should let exactly one redemption win when a code is presented repeatedly")
  void shouldLetExactlyOneRedemptionWinWhenCodeIsPresentedRepeatedly() {
    var issued = pendingCode();
    redeem(issued.code(), "first passphrase");

    var consumed = issued.code();
    assertThatThrownBy(() -> redeem(consumed, "second passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:first passphrase");
  }

  @Test
  @DisplayName("Should release the verification budget when a reset code succeeds")
  void shouldReleaseVerificationBudgetWhenResetCodeSucceeds() {
    var issued = pendingCode();
    var consumedCode = issued.code();
    var wrong = issued.publicId() + ".wrong-secret";
    for (var i = 0; i < 4; i++) {
      assertThatThrownBy(() -> redeem(wrong, "replacement password"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    redeem(consumedCode, "replacement password");
    clock.advance(Duration.ofSeconds(1));
    for (var i = 0; i < 5; i++) {
      assertThatThrownBy(() -> redeem(consumedCode, "another password"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    assertThatThrownBy(() -> redeem(consumedCode, "another password"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:replacement password");
    assertThat(credentialAttempts.attempts())
        .hasSize(10)
        .extracting(attempt -> attempt.target().credentialId())
        .containsOnly(resetCodes.findAll().getFirst().getId());
  }

  @Test
  @DisplayName("Should answer like an unknown code when a reset code is expired")
  void shouldAnswerLikeUnknownCodeWhenResetCodeIsExpired() {
    var issued = pendingCode();
    resetCodes.findAll().getFirst().setExpiresAt(NOW.minusSeconds(1));

    var expiredCode = issued.code();
    assertThatThrownBy(() -> redeem(expiredCode, "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
    assertThatThrownBy(() -> redeem("unknown.secret", "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);
  }

  @Test
  @DisplayName("Should answer like an unknown code when a reset code is malformed")
  void shouldAnswerLikeUnknownCodeWhenResetCodeIsMalformed() {
    assertThatThrownBy(() -> redeem("malformed", "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash()).isEqualTo("old");
  }

  @Test
  @DisplayName("Should answer like an unknown code when the reset secret is wrong")
  void shouldAnswerLikeUnknownCodeWhenResetSecretIsWrong() {
    var issued = pendingCode();
    var wrongCode = issued.publicId() + ".wrong-secret";

    assertThatThrownBy(() -> redeem(wrongCode, "new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash()).isEqualTo("old");
  }

  @Test
  @DisplayName("Should throttle a valid reset code when its verification budget is exhausted")
  void shouldThrottleValidResetCodeWhenVerificationBudgetIsExhausted() {
    var issued = pendingCode();
    var wrongCode = issued.publicId() + ".wrong-secret";
    var validCode = issued.code();
    for (var attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> redeem(wrongCode, "new passphrase"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    assertThatThrownBy(() -> redeem(validCode, "new passphrase"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash()).isEqualTo("old");
  }

  private PasswordResetService serviceUsing(FakeUserAccountRepository accountRepository) {
    return new PasswordResetService(
        resetCodes,
        accountRepository,
        sessions,
        opaqueCodes,
        credentialAttempts.gate(clock),
        new PlainPasswordEncoder(),
        new TransactionTemplate(new FakeTransactionManager()),
        CredentialCodeProperties.builder()
            .invitationTtl(Duration.ofDays(7))
            .passwordResetTtl(Duration.ofHours(1))
            .replacementLockTimeout(Duration.ofSeconds(5))
            .build(),
        clock);
  }

  private void redeem(String code, String newPassword) {
    redeem(service, code, newPassword);
  }

  private static void redeem(PasswordResetService target, String code, String newPassword) {
    target.redeem(
        RedeemPasswordResetCommand.builder()
            .code(code)
            .newPassword(newPassword)
            .ipAddress("192.0.2.26")
            .build());
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

  /** The row is locked two statements earlier, so a zero-row password write cannot happen. */
  private final class PasswordWriteRefusingUserAccountRepository extends FakeUserAccountRepository {

    private PasswordWriteRefusingUserAccountRepository() {
      save(account);
    }

    @Override
    public boolean trySetPasswordHash(UUID accountId, String passwordHash) {
      return false;
    }
  }

  @Test
  @DisplayName("Should refuse the redemption when the journal blocks the attempt")
  void shouldRefuseRedemptionWhenJournalBlocksAttempt() {
    var code = pendingCode().code();
    credentialAttempts.rejectReservations(Duration.ofMinutes(15));

    assertThatThrownBy(() -> redeem(code, "a brand new passphrase"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash()).isEqualTo("old");
  }

  @Test
  @DisplayName("Should journal a failure against the code when the secret is wrong")
  void shouldJournalFailureAgainstCodeWhenSecretIsWrong() {
    var issued = pendingCode();
    var wrongSecret = issued.publicId() + ".wrong-secret";

    assertThatThrownBy(() -> redeem(wrongSecret, "a brand new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target())
                  .isEqualTo(
                      CredentialAttemptTarget.builder()
                          .kind(CredentialKind.PASSWORD_RESET_CODE)
                          .credentialId(resetCodes.findAll().getFirst().getId())
                          .ipAddress("192.0.2.26")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });
  }

  @Test
  @DisplayName("Should journal a failure with no target when the public id is unknown")
  void shouldJournalFailureWithNoTargetWhenPublicIdIsUnknown() {
    assertThatThrownBy(() -> redeem("unknown.secret", "a brand new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.target())
                  .isEqualTo(
                      CredentialAttemptTarget.builder()
                          .kind(CredentialKind.PASSWORD_RESET_CODE)
                          .ipAddress("192.0.2.26")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });
  }

  @Test
  @DisplayName("Should preserve another code's budget when one reset code is exhausted")
  void shouldPreserveAnotherCodesBudgetWhenOneResetCodeIsExhausted() {
    var first = pendingCode();
    var firstCode = first.code();
    var other = pendingCode();
    var wrong = first.publicId() + ".wrong-secret";
    for (var i = 0; i < 5; i++) {
      assertThatThrownBy(() -> redeem(wrong, "replacement password"))
          .isInstanceOf(InvalidOneTimeCodeException.class);
    }

    redeem(other.code(), "replacement password");
    assertThatThrownBy(() -> redeem(firstCode, "another password"))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(accounts.findById(account.getId()).orElseThrow().getPasswordHash())
        .isEqualTo("hashed:replacement password");
  }

  @Test
  @DisplayName("Should journal no attempt when the code is malformed")
  void shouldJournalNoAttemptWhenCodeIsMalformed() {
    assertThatThrownBy(() -> redeem("not-even-a-code", "a brand new passphrase"))
        .isInstanceOf(InvalidOneTimeCodeException.class);

    assertThat(credentialAttempts.attempts()).isEmpty();
  }
}
