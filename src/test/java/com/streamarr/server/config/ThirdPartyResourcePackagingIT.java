package com.streamarr.server.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
@DisplayName("Third-Party Resource Packaging Integration Tests")
class ThirdPartyResourcePackagingIT {

  private static final String NOTICES_ENTRY = "META-INF/THIRD_PARTY_NOTICES.md";
  private static final String LICENSE_ENTRY = "META-INF/LICENSE-APACHE-2.0.txt";
  private static final String APACHE_LICENSE_SHA256 =
      "56dfc19e0dc836e30177332f73e8e6fbc297941acf3d906eec6eaaa46c2c452a";

  @Test
  @DisplayName("Should package AndroidX attribution when application JAR is built")
  void shouldPackageAndroidxAttributionWhenApplicationJarIsBuilt() throws IOException {
    try (var jar = new JarFile(applicationJar().toFile())) {
      var packagedNotices = readEntry(jar, NOTICES_ENTRY);

      assertThat(packagedNotices).isEqualTo(Files.readAllBytes(Path.of("THIRD_PARTY_NOTICES.md")));
      assertThat(new String(packagedNotices, UTF_8))
          .contains(
              "AndroidX (Android Open Source Project)",
              "9748764301e5dce66cbf297f6778fa658768c213",
              "Copyright 2018 The Android Open Source Project",
              "License: Apache License 2.0");
    }
  }

  @Test
  @DisplayName("Should package canonical Apache license when application JAR is built")
  void shouldPackageCanonicalApacheLicenseWhenApplicationJarIsBuilt()
      throws IOException, NoSuchAlgorithmException {
    try (var jar = new JarFile(applicationJar().toFile())) {
      var packagedLicense = readEntry(jar, LICENSE_ENTRY);

      assertThat(packagedLicense)
          .isEqualTo(
              Files.readAllBytes(Path.of("src/main/resources/META-INF/LICENSE-APACHE-2.0.txt")));
      assertThat(sha256(packagedLicense)).isEqualTo(APACHE_LICENSE_SHA256);
    }
  }

  private static Path applicationJar() throws IOException {
    try (var paths = Files.list(Path.of("target"))) {
      var applicationJars =
          paths.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
      assertThat(applicationJars).as("packaged application JAR").hasSize(1);
      return applicationJars.getFirst();
    }
  }

  private static byte[] readEntry(JarFile jar, String entryName) throws IOException {
    var entry = jar.getJarEntry(entryName);
    assertThat(entry).as("packaged JAR entry %s", entryName).isNotNull();
    try (var input = jar.getInputStream(entry)) {
      return input.readAllBytes();
    }
  }

  private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
