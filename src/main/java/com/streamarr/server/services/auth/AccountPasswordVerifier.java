package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class AccountPasswordVerifier {

  private final PasswordEncoder passwordEncoder;
  private final CredentialGuessThrottle throttle;

  public PasswordReauthentication verify(UserAccount account, String password) {
    throttle.registerAccountPasswordAttempt(account.getId());
    var expectedPasswordHash = account.getPasswordHash();
    if (!passwordMatches(account.getId(), expectedPasswordHash, password)) {
      throw new InvalidCredentialsException();
    }
    throttle.resetAccountPasswordAttempts(account.getId());
    return new PasswordReauthentication(account.getId());
  }

  private boolean passwordMatches(UUID accountId, String expectedPasswordHash, String password) {
    try {
      return passwordEncoder.matches(password, expectedPasswordHash);
    } catch (IllegalArgumentException exception) {
      log.error("Stored password hash for account {} is unreadable.", accountId, exception);
      return false;
    }
  }
}
