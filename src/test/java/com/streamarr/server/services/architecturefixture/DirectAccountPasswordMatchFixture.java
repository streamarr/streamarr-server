package com.streamarr.server.services.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** A service that compares an Account password itself instead of using AccountPasswordVerifier. */
@RequiredArgsConstructor
public class DirectAccountPasswordMatchFixture {

  private final Argon2PasswordEncoder passwordEncoder;

  @SuppressWarnings("java:S1144") // ArchUnit inspects this uninvoked bytecode fixture.
  private boolean verify(String password, String passwordHash) {
    return passwordEncoder.matches(password, passwordHash);
  }
}
