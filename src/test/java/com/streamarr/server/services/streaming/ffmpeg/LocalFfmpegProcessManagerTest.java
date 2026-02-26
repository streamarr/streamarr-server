package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.StreamSession;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
class LocalFfmpegProcessManagerTest {

  @TempDir Path tempDir;

  private final LocalFfmpegProcessManager manager = new LocalFfmpegProcessManager();

  @Test
  @DisplayName("Should report running when process is started")
  void shouldReportRunningWhenProcessIsStarted() {
    var sessionId = UUID.randomUUID();

    var process =
        manager.startProcess(
            sessionId, StreamSession.defaultVariant(), List.of("sleep", "30"), tempDir);

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
        sessionId, StreamSession.defaultVariant(), List.of("sleep", "30"), tempDir);

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
            sessionId, StreamSession.defaultVariant(), List.of("echo", "done"), tempDir);
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
  @DisplayName("Should report running for specific variant")
  void shouldReportRunningForSpecificVariant() {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);

    assertThat(manager.isRunning(sessionId, "720p")).isTrue();
    assertThat(manager.isRunning(sessionId, "360p")).isFalse();

    manager.stopProcess(sessionId);
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
            sessionId, StreamSession.defaultVariant(), List.of("bash", "-c", script), tempDir);

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
            StreamSession.defaultVariant(),
            List.of("bash", "-c", "echo 'error output' >&2; exit 1"),
            tempDir);
    process.waitFor();

    await().pollDelay(Duration.ofMillis(200)).until(() -> true);

    // isRunning triggers cleanup + logExitDetails — should not throw
    assertThatNoException().isThrownBy(() -> manager.isRunning(sessionId));
    assertThat(manager.isRunning(sessionId)).isFalse();
  }
}
