package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Password Encoding Tests")
class PasswordEncodingTest {

  private static final Argon2Properties WEAK_PARAMS =
      Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build();

  // OWASP argon2id minimums.
  private static final int MIN_MEMORY_KIB = 19_456;
  private static final int MIN_ITERATIONS = 2;
  private static final int MIN_PARALLELISM = 1;

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

  @Test
  @DisplayName("Should ship Argon2 defaults that meet the OWASP floor")
  void shouldShipArgon2DefaultsThatMeetOwaspFloor() throws Exception {
    // The tests above build deliberately weak encoders, so a silent downgrade of the shipped
    // defaults would otherwise pass unnoticed; pin the packaged application.yml defaults.
    var applicationYaml = readApplicationYaml();

    assertThat(defaultOf(applicationYaml, "AUTH_ARGON2_MEMORY_KIB"))
        .isGreaterThanOrEqualTo(MIN_MEMORY_KIB);
    assertThat(defaultOf(applicationYaml, "AUTH_ARGON2_ITERATIONS"))
        .isGreaterThanOrEqualTo(MIN_ITERATIONS);
    assertThat(defaultOf(applicationYaml, "AUTH_ARGON2_PARALLELISM"))
        .isGreaterThanOrEqualTo(MIN_PARALLELISM);
  }

  private static String readApplicationYaml() throws Exception {
    try (InputStream stream =
        PasswordEncodingTest.class.getClassLoader().getResourceAsStream("application.yml")) {
      assertThat(stream).as("packaged application.yml on the classpath").isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static int defaultOf(String yaml, String environmentVariable) {
    var matcher = Pattern.compile("\\$\\{" + environmentVariable + ":(\\d+)}").matcher(yaml);
    assertThat(matcher.find()).as("default for %s", environmentVariable).isTrue();
    return Integer.parseInt(matcher.group(1));
  }
}
