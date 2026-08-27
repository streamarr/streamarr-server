package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.SessionRevocationReason;
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
  private final OpaqueCodeResolver codeResolver;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;
  private final CredentialCodeProperties properties;
  private final Clock clock;

  public void redeem(String rawCode, String newPassword) {
    var code = resolvePending(rawCode);
    var newPasswordHash = passwordEncoder.encode(newPassword);

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

  private PasswordResetCode resolvePending(String rawCode) {
    return codeResolver.resolvePending(rawCode, resetCodeRepository::findByPublicId);
  }
}
