package com.streamarr.server.services.authorization.cedar;

import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fizzed.jne.JNE;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * cedar-java's uber jar ships its Jackson 2, Guava, and JNE needs as ordinary transitive
 * dependencies, so Spring Boot's dependency management can silently resolve them to versions the
 * native bridge was never built against. Pin what the build actually resolved to what cedar-java
 * 4.10.0 declares; a Cedar upgrade changes these expectations deliberately, never through Renovate.
 */
@Tag("UnitTest")
@DisplayName("Cedar Dependency Alignment Tests")
class CedarDependencyAlignmentTest {

  private static final Pattern JAR_VERSION = Pattern.compile("-(\\d[^/]*)\\.jar!");

  @Test
  @DisplayName("Should resolve the cedar-java uber jar at the pinned version")
  void shouldResolveCedarJavaUberJarAtPinnedVersion() {
    assertThat(jarLocation(BasicAuthorizationEngine.class)).contains("cedar-java-4.10.0-uber.jar");
  }

  @Test
  @DisplayName("Should resolve Jackson 2 at or above the version cedar-java declares")
  void shouldResolveJackson2AtOrAboveVersionCedarJavaDeclares() {
    assertThat(jarLocation(ObjectMapper.class)).contains("jackson-databind-2.");
    assertThat(jarVersion(ObjectMapper.class))
        .satisfies(version -> assertThat(compare(version, "2.20.0")).isNotNegative());
  }

  @Test
  @DisplayName("Should resolve Guava at or above the version cedar-java declares")
  void shouldResolveGuavaAtOrAboveVersionCedarJavaDeclares() {
    assertThat(jarVersion(Preconditions.class))
        .satisfies(version -> assertThat(compare(version, "33.5.0")).isNotNegative());
  }

  @Test
  @DisplayName("Should resolve JNE at exactly the version cedar-java declares")
  void shouldResolveJneAtExactlyVersionCedarJavaDeclares() {
    assertThat(jarLocation(JNE.class)).contains("jne-4.5.3.jar");
  }

  @Test
  @DisplayName("Should keep Cedar out of automatic dependency updates")
  void shouldKeepCedarOutOfAutomaticDependencyUpdates() throws IOException {
    var renovate = Files.readString(Path.of("renovate.json"));

    assertThat(renovate)
        .contains("com.cedarpolicy:cedar-java")
        .containsPattern("\"matchPackageNames\":\\s*\\[\\s*\"com.cedarpolicy:cedar-java\"");
  }

  private static String jarLocation(Class<?> type) {
    var resource = type.getName().replace('.', '/') + ".class";
    var url = type.getClassLoader().getResource(resource);
    assertThat(url).as("class resource for %s", type).isNotNull();
    return url.toString();
  }

  private static String jarVersion(Class<?> type) {
    var matcher = JAR_VERSION.matcher(jarLocation(type));
    assertThat(matcher.find()).as("jar version in %s", jarLocation(type)).isTrue();
    return matcher.group(1);
  }

  private static int compare(String actual, String minimum) {
    var actualParts = actual.replace("-jre", "").split("\\.");
    var minimumParts = minimum.split("\\.");
    for (var index = 0; index < Math.min(actualParts.length, minimumParts.length); index++) {
      var difference =
          Integer.compare(
              Integer.parseInt(actualParts[index]), Integer.parseInt(minimumParts[index]));
      if (difference != 0) {
        return difference;
      }
    }
    return Integer.compare(actualParts.length, minimumParts.length);
  }
}
