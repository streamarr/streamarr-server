package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Profile PIN Hasher Tests")
class ProfilePinHasherTest {

  private final ProfilePinHasher hasher = new ProfilePinHasher(new PlainEncoder());

  @ParameterizedTest
  @ValueSource(strings = {"1234", "12345678"})
  @DisplayName("Should accept PIN when it contains four to eight ASCII digits")
  void shouldAcceptPinWhenItContainsFourToEightAsciiDigits(String pin) {
    assertThat(hasher.isWellFormed(pin)).isTrue();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "123", "123456789", "12/4", "12a4", "１２３４"})
  @DisplayName("Should reject PIN when it is null or outside the ASCII digit format")
  void shouldRejectPinWhenItIsNullOrOutsideTheAsciiDigitFormat(String pin) {
    assertThat(hasher.isWellFormed(pin)).isFalse();
  }

  private static final class PlainEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
      return "encoded:" + rawPassword;
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return encodedPassword.equals(encode(rawPassword));
    }
  }
}
