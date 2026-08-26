package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.OneTimeCredential;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a presented opaque code to its PENDING, unexpired credential: the row is found by
 * publicId, known publicIds are throttled before the constant-time digest comparison, a matching
 * secret releases the budget, and every miss gets the same deliberate answer.
 */
@Component
@RequiredArgsConstructor
class OpaqueCodeResolver {

  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
  private final Clock clock;

  <T extends OneTimeCredential> T resolvePending(
      String rawCode, Function<String, Optional<T>> byPublicId) {
    var presented = opaqueCodes.parse(rawCode).orElseThrow(InvalidOneTimeCodeException::new);
    var credential =
        byPublicId.apply(presented.publicId()).orElseThrow(InvalidOneTimeCodeException::new);
    throttle.registerCodeGuess(presented.publicId());
    if (!opaqueCodes.matches(presented, credential.getSecretDigest())) {
      throw new InvalidOneTimeCodeException();
    }

    throttle.resetCodeGuesses(presented.publicId());
    if (!credential.isRedeemableAt(clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }

    return credential;
  }
}
