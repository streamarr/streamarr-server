package com.streamarr.server.config.security;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

  private static final String ARGON2_ID = "argon2id";
  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;

  /**
   * Delegating encoder per ADR 0016: stored hashes carry their algorithm id (e.g. {@code
   * {argon2id}$argon2id$...}), so rehash-on-login and future algorithm swaps need no schema change.
   * Custom rather than the Spring factory default, whose id-for-encode is still bcrypt.
   */
  @Bean
  public PasswordEncoder passwordEncoder(Argon2Properties properties) {
    var argon2 =
        new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES,
            HASH_LENGTH_BYTES,
            properties.parallelism(),
            properties.memoryKib(),
            properties.iterations());

    return new DelegatingPasswordEncoder(
        ARGON2_ID, Map.of(ARGON2_ID, argon2, "bcrypt", new BCryptPasswordEncoder()));
  }
}
