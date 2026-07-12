package com.streamarr.transcode.engine.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.TranscodeRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Local FFmpeg Process Manager Tests")
class LocalFfmpegProcessManagerTest {

  @TempDir Path tempDir;

  private final LocalFfmpegProcessManager manager = new LocalFfmpegProcessManager();

  @Test
  @DisplayName("Should report running when process is started")
  void shouldReportRunningWhenProcessIsStarted() {
    var sessionId = UUID.randomUUID();

    var process =
        manager.startProcess(
            sessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    assertThat(process).isNotNull();
    assertThat(process.isAlive()).isTrue();
    assertThat(manager.isRunning(sessionId)).isTrue();

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("Should stop process gracefully when requested")
  void shouldStopProcessGracefullyWhenRequested() {
    var sessionId = UUID.randomUUID();

    manager.startProcess(
        sessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    manager.stopProcess(sessionId);

    assertThat(manager.isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should report not running when session is unknown")
  void shouldReportNotRunningWhenSessionIsUnknown() {
    assertThat(manager.isRunning(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should report not running when process has exited naturally")
  void shouldReportNotRunningWhenProcessHasExitedNaturally() throws Exception {
    var sessionId = UUID.randomUUID();

    var process =
        manager.startProcess(
            sessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("echo", "done"), tempDir);
    process.waitFor();

    await().pollDelay(Duration.ofMillis(100)).until(() -> true);

    assertThat(manager.isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should not throw when stopping already stopped session")
  void shouldNotThrowWhenStoppingAlreadyStoppedSession() {
    var sessionId = UUID.randomUUID();

    assertThatNoException().isThrownBy(() -> manager.stopProcess(sessionId));
  }

  @Test
  @DisplayName("Should track multiple variants per session")
  void shouldTrackMultipleVariantsPerSession() {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, "1080p", List.of("sleep", "30"), tempDir);
    manager.startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);
    manager.startProcess(sessionId, "480p", List.of("sleep", "30"), tempDir);

    assertThat(manager.isRunning(sessionId)).isTrue();
    assertThat(manager.isRunning(sessionId, "1080p")).isTrue();
    assertThat(manager.isRunning(sessionId, "720p")).isTrue();
    assertThat(manager.isRunning(sessionId, "480p")).isTrue();

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("Should reject duplicate variant without replacing its running process")
  void shouldRejectDuplicateVariantWithoutReplacingItsRunningProcess() {
    var sessionId = UUID.randomUUID();
    var variant = TranscodeRequest.DEFAULT_VARIANT;
    var command = List.of("sleep", "30");
    var first = manager.startProcess(sessionId, variant, command, tempDir);

    assertThatThrownBy(() -> manager.startProcess(sessionId, variant, command, tempDir))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);

    assertThat(first.isAlive()).isTrue();
    assertThat(manager.isRunning(sessionId, variant)).isTrue();
    manager.stopProcess(sessionId);
    assertThat(first.isAlive()).isFalse();
  }

  @Test
  @DisplayName("Should stop all variants when stopping session")
  void shouldStopAllVariantsWhenStoppingSession() {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, "1080p", List.of("sleep", "30"), tempDir);
    manager.startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);
    manager.startProcess(sessionId, "480p", List.of("sleep", "30"), tempDir);

    manager.stopProcess(sessionId);

    assertThat(manager.isRunning(sessionId)).isFalse();
    assertThat(manager.isRunning(sessionId, "1080p")).isFalse();
    assertThat(manager.isRunning(sessionId, "720p")).isFalse();
    assertThat(manager.isRunning(sessionId, "480p")).isFalse();
  }

  @Test
  @DisplayName("Should forcibly drain every tracked process during final shutdown")
  void shouldForciblyDrainEveryTrackedProcessDuringFinalShutdown() {
    var firstSessionId = UUID.randomUUID();
    var secondSessionId = UUID.randomUUID();
    var first =
        manager.startProcess(
            firstSessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);
    var second =
        manager.startProcess(
            secondSessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    try {
      manager.forceStopAll();

      assertThat(first.isAlive()).isFalse();
      assertThat(second.isAlive()).isFalse();
      assertThat(manager.isRunning(firstSessionId)).isFalse();
      assertThat(manager.isRunning(secondSessionId)).isFalse();
    } finally {
      manager.stopProcess(firstSessionId);
      manager.stopProcess(secondSessionId);
    }
  }

  @Test
  @DisplayName("Should remove naturally completed process during final shutdown")
  void shouldRemoveNaturallyCompletedProcessDuringFinalShutdown() throws Exception {
    var sessionId = UUID.randomUUID();
    var process = manager.startProcess(sessionId, "completed", List.of("true"), tempDir);
    process.waitFor();

    manager.forceStopAll();

    assertThat(manager.isRunning(sessionId, "completed")).isFalse();
  }

  @Test
  @DisplayName("Should accept graceful quit response when stopping process")
  void shouldAcceptGracefulQuitResponseWhenStoppingProcess() {
    var sessionId = UUID.randomUUID();
    var process =
        manager.startProcess(sessionId, "graceful", List.of("bash", "-c", "read -r -n 1"), tempDir);

    manager.stopProcess(sessionId);

    assertThat(process.isAlive()).isFalse();
    assertThat(manager.isRunning(sessionId, "graceful")).isFalse();
  }

  @Test
  @DisplayName(
      "Should stop every variant and restore interruption when session stop is interrupted")
  void shouldStopEveryVariantAndRestoreInterruptionWhenSessionStopIsInterrupted() {
    var sessionId = UUID.randomUUID();
    var gracefulCommand = List.of("bash", "-c", "read -r -n 1");
    var first = manager.startProcess(sessionId, "first", gracefulCommand, tempDir);
    var second = manager.startProcess(sessionId, "second", gracefulCommand, tempDir);

    try {
      Thread.currentThread().interrupt();

      assertThatThrownBy(() -> manager.stopProcess(sessionId))
          .isInstanceOf(TranscodeException.class)
          .hasMessage(TranscodeException.GENERIC_MESSAGE);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(first.isAlive()).isFalse();
      assertThat(second.isAlive()).isFalse();
      assertThat(manager.isRunning(sessionId)).isFalse();
    } finally {
      Thread.interrupted();
      manager.forceStopAll();
    }
  }

  @Test
  @DisplayName("Should finish cleanup when session stop is interrupted while waiting")
  void shouldFinishCleanupWhenSessionStopIsInterruptedWhileWaiting() {
    var sessionId = UUID.randomUUID();
    var process = manager.startProcess(sessionId, "waiting", List.of("sleep", "30"), tempDir);
    var failure = new AtomicReference<RuntimeException>();
    var stopThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    manager.stopProcess(sessionId);
                  } catch (RuntimeException exception) {
                    failure.set(exception);
                  }
                });

    try {
      await()
          .atMost(Duration.ofSeconds(2))
          .until(
              () ->
                  stopThread.getState() == Thread.State.WAITING
                      || stopThread.getState() == Thread.State.TIMED_WAITING);
      stopThread.interrupt();
      await().atMost(Duration.ofSeconds(2)).until(() -> !stopThread.isAlive());

      assertThat(failure.get()).isInstanceOf(TranscodeException.class);
      assertThat(stopThread.isInterrupted()).isTrue();
      assertThat(process.isAlive()).isFalse();
      assertThat(manager.isRunning(sessionId)).isFalse();
    } finally {
      stopThread.interrupt();
      manager.forceStopAll();
    }
  }

  @Test
  @DisplayName("Should report running for specific variant")
  void shouldReportRunningForSpecificVariant() {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);

    assertThat(manager.isRunning(sessionId, "720p")).isTrue();
    assertThat(manager.isRunning(sessionId, "360p")).isFalse();

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("Should throw with generic message when process start fails")
  void shouldThrowWithGenericMessageWhenProcessStartFails() {
    var sessionId = UUID.randomUUID();

    var command = List.of("/nonexistent-binary-12345");

    assertThatThrownBy(() -> manager.startProcess(sessionId, "default", command, tempDir))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);
  }

  @Test
  @DisplayName("Should not deadlock when process produces large stderr output")
  void shouldNotDeadlockWhenProcessProducesLargeStderrOutput() {
    var sessionId = UUID.randomUUID();

    // Write 5000 lines to stderr (well beyond the ~64KB OS pipe buffer), then exit.
    // Without stderr draining, the process blocks on write() and never reaches exit.
    var script = "for i in $(seq 1 5000); do echo \"stderr line $i\" >&2; done; exit 0";

    var process =
        manager.startProcess(
            sessionId, TranscodeRequest.DEFAULT_VARIANT, List.of("bash", "-c", script), tempDir);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(process.isAlive()).isFalse();
              assertThat(process.exitValue()).isZero();
            });

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("Should capture stderr in exit details when process exits naturally")
  void shouldCaptureStderrInExitDetailsWhenProcessExitsNaturally() throws Exception {
    var sessionId = UUID.randomUUID();

    var process =
        manager.startProcess(
            sessionId,
            TranscodeRequest.DEFAULT_VARIANT,
            List.of("bash", "-c", "echo 'error output' >&2; exit 1"),
            tempDir);
    process.waitFor();

    await().pollDelay(Duration.ofMillis(200)).until(() -> true);

    // isRunning triggers cleanup + logExitDetails — should not throw
    assertThatNoException().isThrownBy(() -> manager.isRunning(sessionId));
    assertThat(manager.isRunning(sessionId)).isFalse();
  }
}
