package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Password Encoding Tests")
class PasswordEncodingTest {

  private static final Argon2Properties WEAK_PARAMS =
      Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build();

  private final PasswordEncoderConfig config = new PasswordEncoderConfig();

  @Test
  @DisplayName("Should encode with argon2id id when hashing password")
  void shouldEncodeWithArgon2idIdWhenHashingPassword() {
    var encoder = config.passwordEncoder(WEAK_PARAMS);

    var encoded = encoder.encode("correct horse battery staple");

    assertThat(encoded).startsWith("{argon2id}$argon2id$");
    assertThat(encoder.matches("correct horse battery staple", encoded)).isTrue();
  }

  @Test
  @DisplayName("Should report upgrade needed when params weaker")
  void shouldReportUpgradeNeededWhenParamsWeaker() {
    var weakEncoder = config.passwordEncoder(WEAK_PARAMS);
    var strongerEncoder =
        config.passwordEncoder(
            Argon2Properties.builder().memoryKib(8192).iterations(2).parallelism(1).build());

    var weakEncoded = weakEncoder.encode("hunter2!");

    assertThat(strongerEncoder.upgradeEncoding(weakEncoded)).isTrue();
    assertThat(weakEncoder.upgradeEncoding(weakEncoded)).isFalse();
  }
}
