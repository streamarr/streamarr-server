package com.streamarr.server.controllers.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@RequiredArgsConstructor
public class DirectControllerAccountPasswordMatchFixture {

  private final PasswordEncoder passwordEncoder;

  public boolean verify(String password, String passwordHash) {
    return passwordEncoder.matches(password, passwordHash);
  }
}
