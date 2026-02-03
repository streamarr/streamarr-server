package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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

    var process = manager.startProcess(sessionId, List.of("sleep", "30"), tempDir);

    assertThat(process).isNotNull();
    assertThat(process.isAlive()).isTrue();
    assertThat(manager.isRunning(sessionId)).isTrue();

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("Should stop process gracefully when requested")
  void shouldStopProcessGracefullyWhenRequested() throws Exception {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, List.of("sleep", "30"), tempDir);

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

    var process = manager.startProcess(sessionId, List.of("echo", "done"), tempDir);
    process.waitFor();

    Thread.sleep(100);

    assertThat(manager.isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("Should not throw when stopping already stopped session")
  void shouldNotThrowWhenStoppingAlreadyStoppedSession() {
    var sessionId = UUID.randomUUID();

    manager.stopProcess(sessionId);
  }
}
