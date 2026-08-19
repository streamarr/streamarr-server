package com.streamarr.server.graphql.architecturefixture;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** A resolver that owns password policy by depending on the encoder at all. */
@RequiredArgsConstructor
public class PasswordEncodingResolverFixture {

  private final PasswordEncoder passwordEncoder;

  private String hash(String password) {
    return passwordEncoder.encode(password);
  }
}
