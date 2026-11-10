package com.streamarr.server.services.concurrency;

import static com.streamarr.server.support.ProcessTestSupport.awaitCompletion;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Mutex Factory Collection Tests")
class MutexFactoryCollectionTest {

  private static final Set<String> JVM_OPTION_VARIABLES =
      Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS");

  @TempDir private static Path workspace;

  private static List<String> report;

  // With SoftRefLRUPolicyMSPerMB=0 the collector clears every soft reference that went unused
  // since the collection before.
  @BeforeAll
  static void probeAfterSoftReferencesAreCleared() throws Exception {
    var output = workspace.resolve("report");
    var probe =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-XX:SoftRefLRUPolicyMSPerMB=0",
                "-cp",
                System.getProperty("java.class.path"),
                MutexFactoryCollectionProbe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile());
    // JVM options from the environment could name another collector or policy.
    probe.environment().keySet().removeAll(JVM_OPTION_VARIABLES);
    var process = probe.start();

    awaitCompletion(process, Duration.ofSeconds(30), "the probe completed");

    report = Files.readAllLines(output);
    assertThat(process.exitValue()).as(String.join("\n", report)).isZero();
    assertThat(report).contains("softReferencesCleared=true");
  }

  @Test
  @DisplayName("Should let a mutex be collected when no caller references it")
  void shouldLetAMutexBeCollectedWhenNoCallerReferencesIt() {
    assertThat(report).contains("unreferencedMutexCollected=true");
  }
}
