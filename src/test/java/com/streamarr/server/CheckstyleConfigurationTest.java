package com.streamarr.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Checkstyle Configuration Tests")
class CheckstyleConfigurationTest {

  @TempDir private Path tempDirectory;

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should reject fully qualified name when import can express dependency")
  void shouldRejectFullyQualifiedNameWhenImportCanExpressDependency() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            final class InlineFullyQualifiedName {
              private final java.time.Instant timestamp = java.time.Instant.EPOCH;
            }
            """);

    assertThat(violations).isOne();
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should allow fully qualified name when explicitly suppressed")
  void shouldAllowFullyQualifiedNameWhenExplicitlySuppressed() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            @SuppressWarnings("checkstyle:fullyQualifiedName")
            final class InlineFullyQualifiedName {
              private final java.time.Instant timestamp = java.time.Instant.EPOCH;
            }
            """);

    assertThat(violations).isZero();
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should allow fully qualified name in import declaration")
  void shouldAllowFullyQualifiedNameInImportDeclaration() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            import java.time.Instant;

            final class ImportedName {
              private final Instant timestamp = Instant.EPOCH;
            }
            """);

    assertThat(violations).isZero();
  }

  private int violationsOf(String source) throws Exception {
    var sourceFile = tempDirectory.resolve("InlineFullyQualifiedName.java");
    Files.writeString(sourceFile, source);
    var configuration =
        ConfigurationLoader.loadConfiguration(
            Path.of("checkstyle.xml").toAbsolutePath().toString(),
            new PropertiesExpander(new Properties()));
    var checker = new Checker();
    checker.setModuleClassLoader(Checker.class.getClassLoader());
    checker.configure(configuration);
    try {
      return checker.process(List.of(sourceFile.toFile()));
    } finally {
      checker.destroy();
    }
  }
}
