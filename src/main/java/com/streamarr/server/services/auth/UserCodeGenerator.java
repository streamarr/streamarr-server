package com.streamarr.server.services.auth;

import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates codes in the {@link UserCode} grammar with about 34.6 bits of entropy. Pairing limits
 * their use through expiry, single-use redemption, and the approver's attempt limit (ADR 0021, ADR
 * 0028).
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
