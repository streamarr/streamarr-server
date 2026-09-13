package com.streamarr.server.support;

import static com.streamarr.server.support.ProcessTestSupport.awaitCompletion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Process Test Support Tests")
class ProcessTestSupportTest {

  @TempDir private Path workspace;

  private final List<ProcessHandle> processes = new ArrayList<>();

  @AfterEach
  void stopProcesses() {
    processes.reversed().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  @DisplayName("Should stop the process and its descendants when completion times out")
  void shouldStopTheProcessAndItsDescendantsWhenCompletionTimesOut() throws Exception {
    var process = waitingProcess();

    assertThatThrownBy(
            () -> awaitCompletion(process, Duration.ofMillis(100), "stalled command completed"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("stalled command completed");

    assertThat(processes).noneMatch(ProcessHandle::isAlive);
  }

  @Test
  @DisplayName(
      "Should stop the process and its descendants when the completion wait is interrupted")
  void shouldStopTheProcessAndItsDescendantsWhenTheCompletionWaitIsInterrupted() throws Exception {
    var process = waitingProcess();

    try {
      Thread.currentThread().interrupt();

      assertThatThrownBy(
              () -> awaitCompletion(process, Duration.ofSeconds(5), "stalled command completed"))
          .isInstanceOf(InterruptedException.class);

      assertThat(processes).noneMatch(ProcessHandle::isAlive);
    } finally {
      Thread.interrupted();
    }
  }

  private Process waitingProcess() throws Exception {
    var process =
        new ProcessBuilder(
                "bash",
                "-c",
                """
                bash -c '
                  sleep 60 &
                  printf "%s\\n" "$$" "$!" > descendants
                  wait
                ' &
                wait
                """)
            .directory(workspace.toFile())
            .start();
    processes.add(process.toHandle());
    var descendants = workspace.resolve("descendants");
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(descendants).isRegularFile();
              assertThat(Files.readAllLines(descendants)).hasSize(2);
            });
    processes.addAll(
        Files.readAllLines(descendants).stream()
            .map(Long::parseLong)
            .map(pid -> ProcessHandle.of(pid).orElseThrow())
            .toList());
    return process;
  }
}
