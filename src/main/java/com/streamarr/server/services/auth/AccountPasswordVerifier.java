package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
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
 * Applies the Account's attempt limit before password verification. Disabled Accounts and
 * unreadable hashes perform dummy hashing before returning invalid credentials. Call outside a
 * transaction: Argon2 must not hold a database connection, and journal reservation and completion
 * use separate transactions.
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
        passwordMetadata(account, ipAddress),
        () -> {
          // Compare against the hash read at the start, even if the managed entity changes
          // meanwhile.
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

  private static CredentialAttemptMetadata passwordMetadata(UserAccount account, String ipAddress) {
    return CredentialAttemptMetadata.builder()
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
      // Use the same response and dummy hashing as other invalid credentials.
      log.error("Stored password hash for account {} is unreadable.", accountId, e);
      timingEqualizer.burn(password);
      return false;
    }
  }
}
