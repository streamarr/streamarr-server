package com.streamarr.server.services.auth.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@RequiredArgsConstructor
public class DirectAccountPasswordMatchFixture {

  private final PasswordEncoder passwordEncoder;

  public boolean verify(String password, String passwordHash) {
    return passwordEncoder.matches(password, passwordHash);
  }
}
