package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptTarget;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Shared checkpoint for authenticated Account-password verification. It journals the attempt before
 * Argon2 and equalizes disabled or unreadable Accounts. Never call it inside a transaction: Argon2
 * would pin a pooled connection for its whole run, and the journal's REQUIRES_NEW reservation and
 * completion would each need a second connection while the caller's sat suspended.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AccountPasswordVerifier {

  private final PasswordEncoder passwordEncoder;
  private final PasswordTimingEqualizer timingEqualizer;
  private final CredentialAttemptGate credentialAttempts;

  /**
   * @throws TooManyCredentialAttemptsException when the Account's attempt limit is exhausted
   * @throws InvalidCredentialsException when the Account is disabled, its stored hash is
   *     unreadable, or the password does not match
   */
  public void verify(UserAccount account, String password, String ipAddress) {
    credentialAttempts.attempt(
        passwordTarget(account, ipAddress),
        () -> {
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
        });
  }

  private static CredentialAttemptTarget passwordTarget(UserAccount account, String ipAddress) {
    return CredentialAttemptTarget.builder()
        .kind(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION)
        .accountId(account.getId())
        .ipAddress(ipAddress)
        .build();
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
