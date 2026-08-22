package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Redeeming a password-reset code (ADR 0024 §Account): allowed while the Account is disabled,
 * changes the password, revokes every refresh session, and creates none — a reset never bypasses a
 * disable. Throttled per publicId with one deliberate failure answer; Argon2 runs before the
 * transaction opens.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetRedemptionService {

  private final PasswordResetCodeRepository resetCodeRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final OpaqueCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public void redeem(String rawCode, String newPassword) {
    var code = resolvePending(rawCode);
    var newPasswordHash = passwordEncoder.encode(newPassword);

    transactionTemplate.executeWithoutResult(
        _ -> {
          var now = clock.instant();
          if (!resetCodeRepository.tryRedeem(code.getId(), now)) {
            throw new InvalidOneTimeCodeException();
          }

          if (!userAccountRepository.trySetPasswordHash(code.getAccountId(), newPasswordHash)) {
            throw new InvalidOneTimeCodeException();
          }

          authSessionRepository.revokeAllForAccount(
              code.getAccountId(), SessionRevocationReason.PASSWORD_CHANGE, now);
        });
  }

  private PasswordResetCode resolvePending(String rawCode) {
    var presented = opaqueCodes.parse(rawCode).orElseThrow(InvalidOneTimeCodeException::new);
    throttle.registerCodeGuess(presented.publicId());
    var code =
        resetCodeRepository
            .findByPublicId(presented.publicId())
            .orElseThrow(InvalidOneTimeCodeException::new);
    if (!opaqueCodes.matches(presented, code.getSecretDigest())) {
      throw new InvalidOneTimeCodeException();
    }

    if (code.getStatus() != PasswordResetCodeStatus.PENDING
        || !code.getExpiresAt().isAfter(clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }

    return code;
  }
}
