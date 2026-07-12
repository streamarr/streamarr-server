package com.streamarr.transcode.engine.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.error.TranscodeException;
import com.streamarr.transcode.engine.model.RenditionRequest;
import com.streamarr.transcode.engine.model.TranscodeJobRef;
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

  private static TranscodeJobRef jobRef(UUID sessionId) {
    return new TranscodeJobRef(sessionId, 1L);
  }

  private static FfmpegProcessKey processKey(UUID sessionId, String renditionLabel) {
    return new FfmpegProcessKey(jobRef(sessionId), renditionLabel);
  }

  private Process startProcess(
      UUID sessionId, String renditionLabel, List<String> command, Path workingDir) {
    return manager.startProcess(processKey(sessionId, renditionLabel), command, workingDir);
  }

  private void stopJob(UUID sessionId) {
    manager.stopJob(jobRef(sessionId));
  }

  private boolean isRunning(UUID sessionId) {
    return manager.isRunning(jobRef(sessionId));
  }

  private boolean isRunning(UUID sessionId, String renditionLabel) {
    return manager.isRunning(processKey(sessionId, renditionLabel));
  }

  @Test
  @DisplayName("Should report running when process is started")
  void shouldReportRunningWhenProcessIsStarted() {
    var sessionId = UUID.randomUUID();

    var process =
        startProcess(sessionId, RenditionRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    assertThat(process).isNotNull();
    assertThat(process.isAlive()).isTrue();
    assertThat(isRunning(sessionId)).isTrue();

    stopJob(sessionId);
  }

  @Test
  @DisplayName("Should stop process gracefully when requested")
  void shouldStopProcessGracefullyWhenRequested() {
    var sessionId = UUID.randomUUID();

    startProcess(sessionId, RenditionRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    stopJob(sessionId);

    assertThat(isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should report not running when session is unknown")
  void shouldReportNotRunningWhenSessionIsUnknown() {
    assertThat(isRunning(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("Should observe an unknown process as absent")
  void shouldObserveAnUnknownProcessAsAbsent() {
    var observation = manager.observe(processKey(UUID.randomUUID(), "unknown"));

    assertThat(observation.state()).isEqualTo(FfmpegProcessState.ABSENT);
    assertThat(observation.exitCode()).isEmpty();
  }

  @Test
  @DisplayName("Should observe a live process as running")
  void shouldObserveALiveProcessAsRunning() {
    var sessionId = UUID.randomUUID();
    var key = processKey(sessionId, "running");
    manager.startProcess(key, List.of("bash", "-c", "read -r -n 1"), tempDir);

    try {
      var observation = manager.observe(key);

      assertThat(observation.state()).isEqualTo(FfmpegProcessState.RUNNING);
      assertThat(observation.exitCode()).isEmpty();
    } finally {
      stopJob(sessionId);
    }
  }

  @Test
  @DisplayName("Should retain a clean process completion with its exit code")
  void shouldRetainACleanProcessCompletionWithItsExitCode() throws Exception {
    var key = processKey(UUID.randomUUID(), "completed");
    var process = manager.startProcess(key, List.of("true"), tempDir);
    process.waitFor();

    var observation = manager.observe(key);

    assertThat(observation.state()).isEqualTo(FfmpegProcessState.COMPLETED);
    assertThat(observation.exitCode()).hasValue(0);
    assertThat(manager.observe(key)).isEqualTo(observation);
  }

  @Test
  @DisplayName("Should release terminal observations for the exact job")
  void shouldReleaseTerminalObservationsForTheExactJob() throws Exception {
    var jobRef = jobRef(UUID.randomUUID());
    var firstKey = new FfmpegProcessKey(jobRef, "completed-first");
    var secondKey = new FfmpegProcessKey(jobRef, "completed-second");
    var firstProcess = manager.startProcess(firstKey, List.of("true"), tempDir);
    var secondProcess = manager.startProcess(secondKey, List.of("true"), tempDir);
    firstProcess.waitFor();
    secondProcess.waitFor();
    assertThat(manager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);
    assertThat(manager.observe(secondKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);

    var released = manager.releaseJobObservation(jobRef);

    assertThat(released).isTrue();
    assertThat(manager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.ABSENT);
    assertThat(manager.observe(secondKey).state()).isEqualTo(FfmpegProcessState.ABSENT);
  }

  @Test
  @DisplayName("Should refuse observation release while any exact job process is running")
  void shouldRefuseObservationReleaseWhileAnyExactJobProcessIsRunning() throws Exception {
    var jobRef = jobRef(UUID.randomUUID());
    var completedKey = new FfmpegProcessKey(jobRef, "completed");
    var runningKey = new FfmpegProcessKey(jobRef, "running");
    var completed = manager.startProcess(completedKey, List.of("true"), tempDir);
    completed.waitFor();
    assertThat(manager.observe(completedKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);
    manager.startProcess(runningKey, List.of("bash", "-c", "read -r -n 1"), tempDir);

    try {
      var released = manager.releaseJobObservation(jobRef);

      assertThat(released).isFalse();
      assertThat(manager.observe(completedKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);
      assertThat(manager.observe(runningKey).state()).isEqualTo(FfmpegProcessState.RUNNING);
    } finally {
      manager.stopJob(jobRef);
    }
  }

  @Test
  @DisplayName("Should not release a newer generation through a stale job reference")
  void shouldNotReleaseANewerGenerationThroughAStaleJobReference() throws Exception {
    var jobId = UUID.randomUUID();
    var firstJob = new TranscodeJobRef(jobId, 1L);
    var secondJob = new TranscodeJobRef(jobId, 2L);
    var firstKey = new FfmpegProcessKey(firstJob, "720p");
    var secondKey = new FfmpegProcessKey(secondJob, "720p");
    var firstProcess = manager.startProcess(firstKey, List.of("true"), tempDir);
    var secondProcess = manager.startProcess(secondKey, List.of("true"), tempDir);
    firstProcess.waitFor();
    secondProcess.waitFor();
    assertThat(manager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);
    assertThat(manager.observe(secondKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);

    assertThat(manager.releaseJobObservation(firstJob)).isTrue();

    assertThat(manager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.ABSENT);
    assertThat(manager.observe(secondKey).state()).isEqualTo(FfmpegProcessState.COMPLETED);
  }

  @Test
  @DisplayName("Should release an already absent job observation idempotently")
  void shouldReleaseAnAlreadyAbsentJobObservationIdempotently() {
    var jobRef = jobRef(UUID.randomUUID());

    assertThat(manager.releaseJobObservation(jobRef)).isTrue();
    assertThat(manager.releaseJobObservation(jobRef)).isTrue();
  }

  @Test
  @DisplayName("Should release a terminal process before it has been observed")
  void shouldReleaseATerminalProcessBeforeItHasBeenObserved() throws Exception {
    var jobRef = jobRef(UUID.randomUUID());
    var key = new FfmpegProcessKey(jobRef, "completed");
    var process = manager.startProcess(key, List.of("true"), tempDir);
    process.waitFor();

    assertThat(manager.releaseJobObservation(jobRef)).isTrue();

    assertThat(manager.observe(key).state()).isEqualTo(FfmpegProcessState.ABSENT);
  }

  @Test
  @DisplayName("Should retain a failed process with its nonzero exit code")
  void shouldRetainAFailedProcessWithItsNonzeroExitCode() throws Exception {
    var key = processKey(UUID.randomUUID(), "failed");
    var process = manager.startProcess(key, List.of("bash", "-c", "exit 7"), tempDir);
    process.waitFor();

    var observation = manager.observe(key);

    assertThat(observation.state()).isEqualTo(FfmpegProcessState.FAILED);
    assertThat(observation.exitCode()).hasValue(7);
    assertThat(manager.observe(key)).isEqualTo(observation);
  }

  @Test
  @DisplayName("Should retain requested process stop separately from natural completion")
  void shouldRetainRequestedProcessStopSeparatelyFromNaturalCompletion() {
    var key = processKey(UUID.randomUUID(), "stopped");
    manager.startProcess(key, List.of("bash", "-c", "read -r -n 1"), tempDir);

    manager.stopProcess(key);
    var observation = manager.observe(key);

    assertThat(observation.state()).isEqualTo(FfmpegProcessState.STOPPED);
    assertThat(observation.exitCode()).hasValue(0);
    assertThat(manager.observe(key)).isEqualTo(observation);
  }

  @Test
  @DisplayName("Should replace a terminal observation when the exact process restarts")
  void shouldReplaceATerminalObservationWhenTheExactProcessRestarts() {
    var key = processKey(UUID.randomUUID(), "restarted");
    var gracefulCommand = List.of("bash", "-c", "read -r -n 1");
    manager.startProcess(key, gracefulCommand, tempDir);
    manager.stopProcess(key);
    assertThat(manager.observe(key).state()).isEqualTo(FfmpegProcessState.STOPPED);

    manager.startProcess(key, gracefulCommand, tempDir);

    try {
      assertThat(manager.observe(key).state()).isEqualTo(FfmpegProcessState.RUNNING);
    } finally {
      manager.stopProcess(key);
    }
  }

  @Test
  @DisplayName("Should report not running when process has exited naturally")
  void shouldReportNotRunningWhenProcessHasExitedNaturally() throws Exception {
    var sessionId = UUID.randomUUID();

    var process =
        startProcess(sessionId, RenditionRequest.DEFAULT_VARIANT, List.of("echo", "done"), tempDir);
    process.waitFor();

    await().pollDelay(Duration.ofMillis(100)).until(() -> true);

    assertThat(isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should not throw when stopping already stopped session")
  void shouldNotThrowWhenStoppingAlreadyStoppedSession() {
    var sessionId = UUID.randomUUID();

    assertThatNoException().isThrownBy(() -> stopJob(sessionId));
  }

  @Test
  @DisplayName("Should track multiple variants per session")
  void shouldTrackMultipleVariantsPerSession() {
    var sessionId = UUID.randomUUID();

    startProcess(sessionId, "1080p", List.of("sleep", "30"), tempDir);
    startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);
    startProcess(sessionId, "480p", List.of("sleep", "30"), tempDir);

    assertThat(isRunning(sessionId)).isTrue();
    assertThat(isRunning(sessionId, "1080p")).isTrue();
    assertThat(isRunning(sessionId, "720p")).isTrue();
    assertThat(isRunning(sessionId, "480p")).isTrue();

    stopJob(sessionId);
  }

  @Test
  @DisplayName("Should isolate the same rendition across exact job generations")
  void shouldIsolateTheSameRenditionAcrossExactJobGenerations() {
    var jobId = UUID.randomUUID();
    var firstJob = new TranscodeJobRef(jobId, 1L);
    var secondJob = new TranscodeJobRef(jobId, 2L);
    var firstKey = new FfmpegProcessKey(firstJob, "720p");
    var secondKey = new FfmpegProcessKey(secondJob, "720p");
    var gracefulCommand = List.of("bash", "-c", "read -r -n 1");
    manager.startProcess(firstKey, gracefulCommand, tempDir);
    manager.startProcess(secondKey, gracefulCommand, tempDir);

    try {
      manager.stopJob(firstJob);

      assertThat(manager.observe(firstKey).state()).isEqualTo(FfmpegProcessState.STOPPED);
      assertThat(manager.observe(secondKey).state()).isEqualTo(FfmpegProcessState.RUNNING);
    } finally {
      manager.stopJob(secondJob);
    }
  }

  @Test
  @DisplayName("Should reject duplicate variant without replacing its running process")
  void shouldRejectDuplicateVariantWithoutReplacingItsRunningProcess() {
    var sessionId = UUID.randomUUID();
    var variant = RenditionRequest.DEFAULT_VARIANT;
    var command = List.of("sleep", "30");
    var first = startProcess(sessionId, variant, command, tempDir);

    assertThatThrownBy(() -> startProcess(sessionId, variant, command, tempDir))
        .isInstanceOf(TranscodeException.class)
        .hasMessage(TranscodeException.GENERIC_MESSAGE);

    assertThat(first.isAlive()).isTrue();
    assertThat(isRunning(sessionId, variant)).isTrue();
    stopJob(sessionId);
    assertThat(first.isAlive()).isFalse();
  }

  @Test
  @DisplayName("Should stop all variants when stopping session")
  void shouldStopAllVariantsWhenStoppingSession() {
    var sessionId = UUID.randomUUID();

    startProcess(sessionId, "1080p", List.of("sleep", "30"), tempDir);
    startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);
    startProcess(sessionId, "480p", List.of("sleep", "30"), tempDir);

    stopJob(sessionId);

    assertThat(isRunning(sessionId)).isFalse();
    assertThat(isRunning(sessionId, "1080p")).isFalse();
    assertThat(isRunning(sessionId, "720p")).isFalse();
    assertThat(isRunning(sessionId, "480p")).isFalse();
  }

  @Test
  @DisplayName("Should forcibly drain every tracked process during final shutdown")
  void shouldForciblyDrainEveryTrackedProcessDuringFinalShutdown() {
    var firstSessionId = UUID.randomUUID();
    var secondSessionId = UUID.randomUUID();
    var first =
        startProcess(
            firstSessionId, RenditionRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);
    var second =
        startProcess(
            secondSessionId, RenditionRequest.DEFAULT_VARIANT, List.of("sleep", "30"), tempDir);

    try {
      manager.forceStopAll();

      assertThat(first.isAlive()).isFalse();
      assertThat(second.isAlive()).isFalse();
      assertThat(isRunning(firstSessionId)).isFalse();
      assertThat(isRunning(secondSessionId)).isFalse();
    } finally {
      stopJob(firstSessionId);
      stopJob(secondSessionId);
    }
  }

  @Test
  @DisplayName("Should remove naturally completed process during final shutdown")
  void shouldRemoveNaturallyCompletedProcessDuringFinalShutdown() throws Exception {
    var sessionId = UUID.randomUUID();
    var process = startProcess(sessionId, "completed", List.of("true"), tempDir);
    process.waitFor();

    manager.forceStopAll();

    assertThat(isRunning(sessionId, "completed")).isFalse();
  }

  @Test
  @DisplayName("Should accept graceful quit response when stopping process")
  void shouldAcceptGracefulQuitResponseWhenStoppingProcess() {
    var sessionId = UUID.randomUUID();
    var process =
        startProcess(sessionId, "graceful", List.of("bash", "-c", "read -r -n 1"), tempDir);

    stopJob(sessionId);

    assertThat(process.isAlive()).isFalse();
    assertThat(isRunning(sessionId, "graceful")).isFalse();
  }

  @Test
  @DisplayName(
      "Should stop every variant and restore interruption when session stop is interrupted")
  void shouldStopEveryVariantAndRestoreInterruptionWhenSessionStopIsInterrupted() {
    var sessionId = UUID.randomUUID();
    var gracefulCommand = List.of("bash", "-c", "read -r -n 1");
    var first = startProcess(sessionId, "first", gracefulCommand, tempDir);
    var second = startProcess(sessionId, "second", gracefulCommand, tempDir);

    try {
      Thread.currentThread().interrupt();

      assertThatThrownBy(() -> stopJob(sessionId))
          .isInstanceOf(TranscodeException.class)
          .hasMessage(TranscodeException.GENERIC_MESSAGE);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(first.isAlive()).isFalse();
      assertThat(second.isAlive()).isFalse();
      assertThat(isRunning(sessionId)).isFalse();
    } finally {
      Thread.interrupted();
      manager.forceStopAll();
    }
  }

  @Test
  @DisplayName("Should finish cleanup when session stop is interrupted while waiting")
  void shouldFinishCleanupWhenSessionStopIsInterruptedWhileWaiting() {
    var sessionId = UUID.randomUUID();
    var process = startProcess(sessionId, "waiting", List.of("sleep", "30"), tempDir);
    var failure = new AtomicReference<RuntimeException>();
    var stopThread =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    stopJob(sessionId);
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
      assertThat(isRunning(sessionId)).isFalse();
    } finally {
      stopThread.interrupt();
      manager.forceStopAll();
    }
  }

  @Test
  @DisplayName("Should report running for specific variant")
  void shouldReportRunningForSpecificVariant() {
    var sessionId = UUID.randomUUID();

    startProcess(sessionId, "720p", List.of("sleep", "30"), tempDir);

    assertThat(isRunning(sessionId, "720p")).isTrue();
    assertThat(isRunning(sessionId, "360p")).isFalse();

    stopJob(sessionId);
  }

  @Test
  @DisplayName("Should throw with generic message when process start fails")
  void shouldThrowWithGenericMessageWhenProcessStartFails() {
    var sessionId = UUID.randomUUID();

    var command = List.of("/nonexistent-binary-12345");

    assertThatThrownBy(() -> startProcess(sessionId, "default", command, tempDir))
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
        startProcess(
            sessionId, RenditionRequest.DEFAULT_VARIANT, List.of("bash", "-c", script), tempDir);

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(process.isAlive()).isFalse();
              assertThat(process.exitValue()).isZero();
            });

    stopJob(sessionId);
  }

  @Test
  @DisplayName("Should capture stderr in exit details when process exits naturally")
  void shouldCaptureStderrInExitDetailsWhenProcessExitsNaturally() throws Exception {
    var sessionId = UUID.randomUUID();

    var process =
        startProcess(
            sessionId,
            RenditionRequest.DEFAULT_VARIANT,
            List.of("bash", "-c", "echo 'error output' >&2; exit 1"),
            tempDir);
    process.waitFor();

    await().pollDelay(Duration.ofMillis(200)).until(() -> true);

    // isRunning triggers cleanup + logExitDetails — should not throw
    assertThatNoException().isThrownBy(() -> isRunning(sessionId));
    assertThat(isRunning(sessionId)).isFalse();
  }
}
