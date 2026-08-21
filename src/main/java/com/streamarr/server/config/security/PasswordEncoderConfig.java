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
    var encoders =
        Map.<String, PasswordEncoder>of(ARGON2_ID, argon2, "bcrypt", new BCryptPasswordEncoder());

    return new StrictDelegatingPasswordEncoder(ARGON2_ID, encoders);
  }

  private static final class StrictDelegatingPasswordEncoder extends DelegatingPasswordEncoder {

    private static final String ID_PREFIX = "{";
    private static final String ID_SUFFIX = "}";

    private final Map<String, PasswordEncoder> encoders;

    private StrictDelegatingPasswordEncoder(
        String idForEncode, Map<String, PasswordEncoder> encoders) {
      super(idForEncode, encoders);
      this.encoders = Map.copyOf(encoders);
    }

    @Override
    protected boolean matchesNonNull(String rawPassword, String encodedPassword) {
      validateRecognizedEncoding(encodedPassword);
      return super.matchesNonNull(rawPassword, encodedPassword);
    }

    private void validateRecognizedEncoding(String encodedPassword) {
      var suffixIndex = encodedPassword.indexOf(ID_SUFFIX);
      if (!encodedPassword.startsWith(ID_PREFIX) || suffixIndex < 0) {
        return;
      }
      var encoder = encoders.get(encodedPassword.substring(ID_PREFIX.length(), suffixIndex));
      if (encoder == null) {
        return;
      }
      encoder.upgradeEncoding(encodedPassword.substring(suffixIndex + ID_SUFFIX.length()));
    }
  }
}
