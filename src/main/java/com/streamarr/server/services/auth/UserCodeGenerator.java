package com.streamarr.server.services.auth;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Mints user codes in the {@link UserCode} grammar. Roughly 34.6 bits — far too little to be a
 * secret, which is why a code is single-use, short-lived, and behind the guessing budget. Entropy
 * here only has to make collisions between concurrently outstanding codes negligible.
 */
@Component
public class UserCodeGenerator {

  private final SecureRandom secureRandom = new SecureRandom();

  public String generate() {
    var code = new StringBuilder(UserCode.LENGTH);
    for (var position = 0; position < UserCode.LENGTH; position++) {
      code.append(UserCode.ALPHABET.charAt(secureRandom.nextInt(UserCode.ALPHABET.length())));
    }
    return code.toString();
  }
}
