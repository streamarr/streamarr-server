package com.streamarr.server.services.auth;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mints user codes in the {@link UserCode} grammar. Roughly 34.6 bits — far too little to be a
 * durable secret — so codes are single-use and short-lived, while the firewall owns volumetric
 * protection for unresolved guesses.
 */
@Component
public class UserCodeGenerator {

  private final SecureRandom secureRandom;

  @Autowired
  UserCodeGenerator() {
    this(new SecureRandom());
  }

  UserCodeGenerator(SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
  }

  public String generate() {
    var code = new StringBuilder(UserCode.LENGTH);
    for (var position = 0; position < UserCode.LENGTH; position++) {
      code.append(UserCode.ALPHABET.charAt(secureRandom.nextInt(UserCode.ALPHABET.length())));
    }
    return code.toString();
  }
}
