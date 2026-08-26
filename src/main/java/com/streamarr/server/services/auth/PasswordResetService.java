package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redeeming a password-reset code (ADR 0024 §Account): allowed while the Account is disabled,
 * changes the password, revokes every refresh session, and creates none — a reset never bypasses a
 * disable. Throttled per publicId with one deliberate failure answer; Argon2 runs before the
 * transaction opens, and the Account row lock waits no longer than the configured lock timeout.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

  private final PasswordResetCodeRepository resetCodeRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialAttemptGate credentialAttempts;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;
  private final CredentialCodeProperties properties;
  private final Clock clock;

  public void redeem(RedeemPasswordResetCommand command) {
    var code = resolvePending(command.code(), command.ipAddress());
    var newPasswordHash = passwordEncoder.encode(command.newPassword());

    transactionTemplate.executeWithoutResult(
        _ -> {
          var locked =
              userAccountRepository.lockByIds(
                  Set.of(code.getAccountId()), properties.replacementLockTimeout());
          if (!locked.contains(code.getAccountId())) {
            // The code row is deleted with its Account, so the code itself is gone too.
            throw OpaqueCodeResolver.rejected(
                OpaqueCodeResolver.MissReason.ACCOUNT_GONE, code.getPublicId());
          }

          var now = clock.instant();
          if (!resetCodeRepository.markRedeemedIfPendingAndUnexpired(code.getId(), now)) {
            throw OpaqueCodeResolver.rejected(
                OpaqueCodeResolver.MissReason.LOST_RACE, code.getPublicId());
          }

          if (!userAccountRepository.trySetPasswordHash(code.getAccountId(), newPasswordHash)) {
            // The Account row is held FOR UPDATE two statements earlier; zero rows is a defect.
            throw new IllegalStateException(
                "Password write for locked Account %s changed no row"
                    .formatted(code.getAccountId()));
          }

          authSessionRepository.revokeAllForAccount(
              code.getAccountId(), SessionRevocationReason.PASSWORD_CHANGE, now);
        });
  }

  private PasswordResetCode resolvePending(String rawCode, String ipAddress) {
    var presented = opaqueCodes.parse(rawCode).orElseThrow(InvalidOneTimeCodeException::new);
    var code = resetCodeRepository.findByPublicId(presented.publicId()).orElse(null);
    return credentialAttempts.attempt(
        codeTarget(code, ipAddress),
        () -> {
          if (code == null) {
            throw new InvalidOneTimeCodeException();
          }
          if (!opaqueCodes.matches(presented, code.getSecretDigest())) {
            throw new InvalidOneTimeCodeException();
          }
          if (code.getStatus() != PasswordResetCodeStatus.PENDING
              || !code.getExpiresAt().isAfter(clock.instant())) {
            throw new InvalidOneTimeCodeException();
          }

          return code;
        });
  }

  private static CredentialAttemptTarget codeTarget(PasswordResetCode code, String ipAddress) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.PASSWORD_RESET_CODE)
        .credentialId(code == null ? null : code.getId())
        .ipAddress(ipAddress)
        .build();
  }
}
