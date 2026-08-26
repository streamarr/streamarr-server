package com.streamarr.server.fakes;

import org.springframework.security.crypto.password.PasswordEncoder;

/** A reversible stand-in for Argon2: the hash is the raw value behind a fixed prefix. */
public final class PlainPasswordEncoder implements PasswordEncoder {

  @Override
  public String encode(CharSequence rawPassword) {
    return "hashed:" + rawPassword;
  }

  @Override
  public boolean matches(CharSequence rawPassword, String encodedPassword) {
    return encode(rawPassword).equals(encodedPassword);
  }
}
