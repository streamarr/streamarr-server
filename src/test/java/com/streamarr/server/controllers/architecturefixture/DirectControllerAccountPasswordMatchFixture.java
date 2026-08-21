package com.streamarr.server.controllers.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A controller that compares an Account password itself instead of calling a service. */
@RequiredArgsConstructor
public class DirectControllerAccountPasswordMatchFixture {

  private final PasswordEncoder passwordEncoder;

  @SuppressWarnings("java:S1144")
  private boolean verify(String password, String passwordHash) {
    return passwordEncoder.matches(password, passwordHash);
  }
}
