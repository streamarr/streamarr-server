package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The single checkpoint for verifying an authenticated Account's password — password change,
 * reauthentication, and every later Account-password action share its budget and its rules. Login
 * keeps its own path because it has no authenticated Account yet and a distinct throttle key (email
 * + source).
 *
 * <p>Order of operations: reserve the ACCOUNT_PASSWORD budget first — an exhausted budget rejects
 * with no Argon2 work at all; that 429 is deliberately distinguishable because throttling is the
 * point. Every path past the reservation performs exactly one full-cost Argon2 operation: a
 * disabled Account burns the equalizer, a readable hash runs the real comparison, an unreadable
 * hash (a cheap parse failure) burns the equalizer. Response time therefore never reveals whether
 * the Account is enabled or its hash is intact.
 *
 * <p>Never called inside a transaction: Argon2 work would pin a pooled connection for its whole
 * duration.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AccountPasswordVerifier {

  private final PasswordEncoder passwordEncoder;
  private final PasswordTimingEqualizer timingEqualizer;
  private final CredentialGuessThrottle throttle;

  /**
   * @throws TooManyCredentialAttemptsException when the Account's password budget is exhausted
   * @throws InvalidCredentialsException when the Account is disabled, its stored hash is
   *     unreadable, or the password does not match
   */
  public void verify(UserAccount account, String password) {
    throttle.registerAccountPasswordAttempt(account.getId());
    // Snapshot before the slow comparison: a password correct when verification begins is
    // sufficient, even if a concurrent change lands on the managed entity meanwhile.
    var expectedPasswordHash = account.getPasswordHash();
    if (!account.isEnabled()) {
      timingEqualizer.burn(password);
      throw new InvalidCredentialsException();
    }

    if (!passwordMatches(account.getId(), expectedPasswordHash, password)) {
      throw new InvalidCredentialsException();
    }

    throttle.resetAccountPasswordAttempts(account.getId());
  }

  private boolean passwordMatches(UUID accountId, String expectedPasswordHash, String password) {
    if (expectedPasswordHash == null
        || expectedPasswordHash.isEmpty()
        || (expectedPasswordHash.startsWith("{") && expectedPasswordHash.endsWith("}"))) {
      log.error("Stored password hash for account {} is unreadable.", accountId);
      timingEqualizer.burn(password);
      return false;
    }

    try {
      return passwordEncoder.matches(password, expectedPasswordHash);
    } catch (IllegalArgumentException e) {
      // An unreadable stored hash must fail like a wrong password, not escape as a raw error
      // that marks the account's broken state.
      log.error("Stored password hash for account {} is unreadable.", accountId, e);
      timingEqualizer.burn(password);
      return false;
    }
  }
}
