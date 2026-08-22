package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Password Timing Equalizer Tests")
class PasswordTimingEqualizerTest {

  @Test
  @DisplayName("Should reuse startup hash when multiple passwords burned")
  void shouldReuseStartupHashWhenMultiplePasswordsBurned() {
    var encoder = new RecordingPasswordEncoder();
    var equalizer = new PasswordTimingEqualizer(encoder);

    equalizer.burn("first password");
    equalizer.burn("second password");

    assertThat(encoder.encodedPasswords()).hasSize(1);
    assertThat(encoder.comparedHashes()).containsExactly("equalizer-hash", "equalizer-hash");
  }

  private static final class RecordingPasswordEncoder implements PasswordEncoder {

    private final List<String> encodedPasswords = new ArrayList<>();
    private final List<String> comparedHashes = new ArrayList<>();

    @Override
    public String encode(CharSequence rawPassword) {
      encodedPasswords.add(rawPassword.toString());
      return "equalizer-hash";
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      comparedHashes.add(encodedPassword);
      return false;
    }

    private List<String> encodedPasswords() {
      return encodedPasswords;
    }

    private List<String> comparedHashes() {
      return comparedHashes;
    }
  }
}
