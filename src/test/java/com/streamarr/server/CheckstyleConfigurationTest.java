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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
  @DisplayName("Should allow fully qualified name when suppressed on its method")
  void shouldAllowFullyQualifiedNameWhenExplicitlySuppressed() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            final class InlineFullyQualifiedName {
              @SuppressWarnings("checkstyle:fullyQualifiedName")
              void run() {
                var timestamp = java.time.Instant.EPOCH;
              }
            }
            """);

    assertThat(violations).isZero();
  }

  @Test
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @DisplayName("Should reject fully qualified name suppression on a class")
  void shouldRejectFullyQualifiedNameSuppressionOnClass() throws Exception {
    var violations =
        violationsOf(
            """
            package example;

            @SuppressWarnings("checkstyle:fullyQualifiedName")
            final class InlineFullyQualifiedName {
              private final java.time.Instant timestamp = java.time.Instant.EPOCH;
            }
            """);

    assertThat(violations).isOne();
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("unseparatedControlFlowBlocks")
  @DisplayName(
      "Should reject missing blank line when a completed control-flow block precedes a statement")
  void shouldRejectMissingBlankLineWhenCompletedControlFlowBlockPrecedesStatement(
      String blockKind, String runMethod) throws Exception {
    assertThat(controlFlowViolationsOf(exampleClassWith(runMethod))).as(blockKind).isOne();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("separatedOrTerminalControlFlowBlocks")
  @DisplayName("Should allow control-flow block when a blank line follows it or its body ends")
  void shouldAllowControlFlowBlockWhenBlankLineFollowsItOrItsBodyEnds(
      String blockKind, String runMethod) throws Exception {
    assertThat(controlFlowViolationsOf(exampleClassWith(runMethod))).as(blockKind).isZero();
  }

  @Test
  @DisplayName("Should keep control-flow separation violations at or below the legacy baseline")
  void shouldKeepControlFlowSeparationViolationsAtOrBelowLegacyBaseline() throws Exception {
    try (var mainSource = Files.walk(Path.of("src/main/java"));
        var testSource = Files.walk(Path.of("src/test/java"))) {
      var sourceFiles =
          Stream.concat(mainSource, testSource)
              .filter(CheckstyleConfigurationTest::isInspectedSource)
              .map(Path::toFile)
              .toList();

      assertThat(
              filesExceedingBaseline(
                  controlFlowViolationCountsOf(sourceFiles), legacyControlFlowBaseline()))
          .as(
              "control-flow separation violations may only go down: insert the blank line, or"
                  + " keep a legacy file's baseline entry at its current count")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("Should accept lowered control-flow counts when files fall below the baseline")
  void shouldAcceptLoweredControlFlowCountsWhenFilesFallBelowBaseline() {
    var violationCounts = Map.of("src/main/java/Legacy.java", 1);
    var baseline = Map.of("src/main/java/Legacy.java", 2, "src/main/java/Deleted.java", 3);

    assertThat(filesExceedingBaseline(violationCounts, baseline)).isEmpty();
  }

  @Test
  @DisplayName("Should reject raised control-flow count when a file exceeds its baseline")
  void shouldRejectRaisedControlFlowCountWhenFileExceedsItsBaseline() {
    var violationCounts = Map.of("src/main/java/Legacy.java", 3);
    var baseline = Map.of("src/main/java/Legacy.java", 2);

    assertThat(filesExceedingBaseline(violationCounts, baseline))
        .singleElement()
        .asString()
        .contains("src/main/java/Legacy.java", "3", "2");
  }

  @Test
  @DisplayName("Should reject control-flow violation when the file is absent from the baseline")
  void shouldRejectControlFlowViolationWhenFileIsAbsentFromBaseline() {
    var violationCounts = Map.of("src/main/java/Unlisted.java", 1);
    var baseline = Map.of("src/main/java/Legacy.java", 2);

    assertThat(filesExceedingBaseline(violationCounts, baseline))
        .singleElement()
        .asString()
        .contains("src/main/java/Unlisted.java");
  }

  private static Stream<Arguments> unseparatedControlFlowBlocks() {
    return Stream.of(
        Arguments.of(
            "comment line in place of the blank line",
            """
            void run(boolean enabled) {
              if (enabled) {
                start();
              }
              // finish once the conditional completes
              finish();
            }
            """),
        Arguments.of(
            "trailing comment on the closing brace",
            """
            void run(boolean enabled) {
              if (enabled) {
                start();
              } // conditional completes here
              finish();
            }
            """),
        Arguments.of(
            "multi-line statement anchored on a later line",
            """
            void run(String name) {
              if (name.isEmpty()) {
                start();
              }
              name
                  .lines()
                  .count();
            }
            """),
        Arguments.of(
            "blank line inside the following statement's inner lambda",
            """
            void run(String name) {
              if (name.isEmpty()) {
                start();
              }
              name
                  .lines()
                  .map(line -> {
                    start();

                    return line;
                  })
                  .count();
            }
            """),
        Arguments.of(
            "for loop",
            """
            void run(int count) {
              for (var i = 0; i < count; i++) {
                start();
              }
              finish();
            }
            """),
        Arguments.of(
            "while loop",
            """
            void run(int count) {
              while (count > 0) {
                count--;
              }
              finish();
            }
            """),
        Arguments.of(
            "do-while loop",
            """
            void run(int count) {
              do {
                count--;
              } while (count > 0);
              finish();
            }
            """),
        Arguments.of(
            "switch statement",
            """
            void run(int count) {
              switch (count) {
                case 0 -> start();
                default -> finish();
              }
              finish();
            }
            """),
        Arguments.of(
            "try-finally",
            """
            void run() {
              try {
                start();
              } finally {
                start();
              }
              finish();
            }
            """),
        Arguments.of(
            "synchronized block",
            """
            void run() {
              synchronized (this) {
                start();
              }
              finish();
            }
            """));
  }

  private static Stream<Arguments> separatedOrTerminalControlFlowBlocks() {
    return Stream.of(
        Arguments.of(
            "blank line then a comment before the statement",
            """
            void run(boolean enabled) {
              if (enabled) {
                start();
              }

              // finish once the conditional completes
              finish();
            }
            """),
        Arguments.of(
            "block ends the method body",
            """
            void run(boolean enabled) {
              start();
              if (enabled) {
                finish();
              }
            }
            """),
        Arguments.of(
            "block ends the enclosing loop body",
            """
            void run(int count) {
              for (var i = 0; i < count; i++) {
                if (i == 0) {
                  start();
                }
              }

              finish();
            }
            """),
        Arguments.of(
            "else continues the conditional",
            """
            void run(boolean enabled) {
              if (enabled) {
                start();
              } else {
                start();
              }

              finish();
            }
            """));
  }

  private static String exampleClassWith(String runMethod) {
    return """
        package example;

        final class Example {
        %s
          private void start() {}

          private void finish() {}
        }
        """
        .formatted(runMethod.indent(2));
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

    Map<String, Integer> baseline = new HashMap<>();
    baselineProperties
        .stringPropertyNames()
        .forEach(name -> baseline.put(name, Integer.valueOf(baselineProperties.getProperty(name))));
    return baseline;
  }

  private static List<String> filesExceedingBaseline(
      Map<String, Integer> violationCounts, Map<String, Integer> baseline) {
    return violationCounts.entrySet().stream()
        .filter(entry -> entry.getValue() > baseline.getOrDefault(entry.getKey(), 0))
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                "%s: %d violations, baseline allows %d"
                    .formatted(
                        entry.getKey(), entry.getValue(), baseline.getOrDefault(entry.getKey(), 0)))
        .toList();
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

    private final Map<String, Integer> counts = new HashMap<>();

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
