package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.OneTimeCredential;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves a presented opaque code to its PENDING, unexpired credential: the row is found by
 * publicId, known publicIds are throttled before the constant-time digest comparison, a matching
 * secret releases the budget, and every miss gets the same deliberate answer. The server log alone
 * records which miss it was, so a guessing burst and a broken deployment stay distinguishable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OpaqueCodeResolver {

  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
  private final Clock clock;

  <T extends OneTimeCredential> T resolvePending(
      String rawCode, Function<String, Optional<T>> byPublicId) {
    var presented =
        opaqueCodes.parse(rawCode).orElseThrow(() -> rejected(MissReason.MALFORMED, "-"));
    var publicId = presented.publicId();
    var credential =
        byPublicId
            .apply(publicId)
            .orElseThrow(() -> rejected(MissReason.UNKNOWN_PUBLIC_ID, publicId));
    throttle.registerCodeGuess(publicId);
    if (!opaqueCodes.matches(presented, credential.getSecretDigest())) {
      throw rejected(MissReason.DIGEST_MISMATCH, publicId);
    }

    throttle.resetCodeGuesses(publicId);
    if (!credential.isRedeemableAt(clock.instant())) {
      throw rejected(MissReason.NOT_REDEEMABLE, publicId);
    }

    return credential;
  }

  /** The publicId is stored in plaintext and never the secret, so it may be logged. */
  static InvalidOneTimeCodeException rejected(MissReason reason, String publicId) {
    log.debug("Opaque code rejected: reason={} publicId={}", reason, publicId);
    return new InvalidOneTimeCodeException();
  }

  enum MissReason {
    MALFORMED,
    UNKNOWN_PUBLIC_ID,
    DIGEST_MISMATCH,
    NOT_REDEEMABLE,
    /** A conditional transition found the row already decided by a concurrent winner. */
    LOST_RACE,
    /** The Account and its reset codes were deleted between resolution and the lock. */
    ACCOUNT_GONE
  }
}
