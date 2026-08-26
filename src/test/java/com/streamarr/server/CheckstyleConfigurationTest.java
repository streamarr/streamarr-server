package com.streamarr.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;
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

  @Test
  @DisplayName("Should reject nested conditional blocks")
  void shouldRejectNestedConditionalBlocks() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            final class NestedConditional {
              void run(boolean enabled, boolean authorized) {
                if (enabled) {
                  if (authorized) {
                    start();
                  }
                }
              }

              private void start() {}
            }
            """);

    assertThat(violations).isOne();
  }

  @Test
  @DisplayName("Should reject missing blank line after completed conditional block")
  void shouldRejectMissingBlankLineAfterCompletedConditionalBlock() throws Exception {
    var violations =
        controlFlowViolationsOf(
            """
            package example;

            final class UnseparatedConditional {
              void run(boolean enabled) {
                if (enabled) {
                  start();
                }
                finish();
              }

              private void start() {}

              private void finish() {}
            }
            """);

    assertThat(violations).isOne();
  }

  @Test
  @DisplayName("Should allow a blank line after a completed conditional block")
  void shouldAllowBlankLineAfterCompletedConditionalBlock() throws Exception {
    var violations =
        controlFlowViolationsOf(
            """
            package example;

            final class SeparatedConditional {
              void run(boolean enabled) {
                if (enabled) {
                  start();
                }

                finish();
              }

              private void start() {}

              private void finish() {}
            }
            """);

    assertThat(violations).isZero();
  }

  @Test
  @DisplayName("Should introduce no control-flow separation beyond legacy baseline")
  void shouldIntroduceNoControlFlowSeparationBeyondLegacyBaseline() throws Exception {
    try (var mainSource = Files.walk(Path.of("src/main/java"));
        var testSource = Files.walk(Path.of("src/test/java"))) {
      var sourceFiles =
          Stream.concat(mainSource, testSource)
              .filter(CheckstyleConfigurationTest::isInspectedSource)
              .map(Path::toFile)
              .toList();

      assertThat(controlFlowViolationCountsOf(sourceFiles)).isEqualTo(legacyControlFlowBaseline());
    }
  }

  private int violationsOf(String source) throws Exception {
    return violationsOf(source, Path.of("checkstyle.xml"));
  }

  private int controlFlowViolationsOf(String source) throws Exception {
    return violationsOf(source, Path.of("src/test/resources/checkstyle/control-flow.xml"));
  }

  private Map<String, Integer> controlFlowViolationCountsOf(List<File> sourceFiles)
      throws Exception {
    var checker = checkerFor(Path.of("src/test/resources/checkstyle/control-flow.xml"));
    var violationCounts = new ViolationCounts();
    checker.addListener(violationCounts);
    try {
      checker.process(sourceFiles);
      return violationCounts.snapshot();
    } finally {
      checker.destroy();
    }
  }

  private Map<String, Integer> legacyControlFlowBaseline() throws Exception {
    var baselineProperties = new Properties();
    try (var input =
        Files.newInputStream(
            Path.of("src/test/resources/checkstyle/control-flow-baseline.properties"))) {
      baselineProperties.load(input);
    }

    Map<String, Integer> baseline = new TreeMap<>();
    baselineProperties
        .stringPropertyNames()
        .forEach(name -> baseline.put(name, Integer.valueOf(baselineProperties.getProperty(name))));
    return baseline;
  }

  private int violationsOf(String source, Path configurationPath) throws Exception {
    var sourceFile = tempDirectory.resolve("InlineFullyQualifiedName.java");
    Files.writeString(sourceFile, source);
    return violationsOf(List.of(sourceFile.toFile()), configurationPath);
  }

  private int violationsOf(List<File> sourceFiles, Path configurationPath) throws Exception {
    var checker = checkerFor(configurationPath);
    try {
      return checker.process(sourceFiles);
    } finally {
      checker.destroy();
    }
  }

  private static boolean isInspectedSource(Path path) {
    var slashSeparatedPath = slashSeparated(path);
    return slashSeparatedPath.endsWith(".java") && !slashSeparatedPath.contains("/jooq/generated/");
  }

  private static String slashSeparated(Path path) {
    return path.toString().replace(File.separatorChar, '/');
  }

  private Checker checkerFor(Path configurationPath) throws Exception {
    var configuration =
        ConfigurationLoader.loadConfiguration(
            configurationPath.toAbsolutePath().toString(),
            new PropertiesExpander(new Properties()));
    var checker = new Checker();
    checker.setModuleClassLoader(CheckstyleConfigurationTest.class.getClassLoader());
    checker.configure(configuration);
    return checker;
  }

  private static final class ViolationCounts implements AuditListener {

    private final Map<String, Integer> counts = new TreeMap<>();

    @Override
    public void auditStarted(AuditEvent event) {
      // no audit-level state to prepare
    }

    @Override
    public void auditFinished(AuditEvent event) {
      // counts are read through snapshot()
    }

    @Override
    public void fileStarted(AuditEvent event) {
      // no per-file state to prepare
    }

    @Override
    public void fileFinished(AuditEvent event) {
      // files without violations stay absent from counts
    }

    @Override
    public void addError(AuditEvent event) {
      var relativePath =
          slashSeparated(
              Path.of("")
                  .toAbsolutePath()
                  .normalize()
                  .relativize(Path.of(event.getFileName()).toAbsolutePath().normalize()));
      counts.merge(relativePath, 1, Integer::sum);
    }

    @Override
    public void addException(AuditEvent event, Throwable failure) {
      throw new AssertionError("Checkstyle could not inspect " + event.getFileName(), failure);
    }

    private Map<String, Integer> snapshot() {
      return Map.copyOf(counts);
    }
  }
}
