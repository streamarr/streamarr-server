package com.streamarr.server.services.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A service that compares an Account password itself instead of using AccountPasswordVerifier. */
@RequiredArgsConstructor
public class DirectAccountPasswordMatchFixture {

  private final PasswordEncoder passwordEncoder;

  private boolean verify(String password, String passwordHash) {
    return passwordEncoder.matches(password, passwordHash);
  }
}
