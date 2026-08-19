package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The single checkpoint for verifying an authenticated Account's password (password change and,
 * later, reauthentication). Login keeps its own path because it has no authenticated Account yet
 * and a distinct throttle key (email + source).
 */
@Component
@RequiredArgsConstructor
public class AccountPasswordVerifier {

  private final PasswordEncoder passwordEncoder;

  public void verify(UserAccount account, String password) {
    if (!passwordEncoder.matches(password, account.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
  }
}
