package com.streamarr.server.config;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Third-Party Resource Packaging Tests")
class ThirdPartyResourcePackagingTest {

  @Test
  @DisplayName("Should package AndroidX attribution and Apache license")
  void shouldPackageAndroidxAttributionAndApacheLicense() throws IOException {
    try (var notices =
            Objects.requireNonNull(
                getClass().getResourceAsStream("/META-INF/THIRD_PARTY_NOTICES.md"));
        var license =
            Objects.requireNonNull(
                getClass().getResourceAsStream("/META-INF/LICENSE-APACHE-2.0.txt"))) {
      assertThat(new String(notices.readAllBytes(), UTF_8))
          .contains("AndroidX (Android Open Source Project)", "Apache License 2.0");
      assertThat(new String(license.readAllBytes(), UTF_8))
          .contains("Apache License", "Version 2.0, January 2004");
    }
  }
}
