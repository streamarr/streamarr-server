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
  @DisplayName("shouldStartProcessAndReportRunning")
  void shouldStartProcessAndReportRunning() {
    var sessionId = UUID.randomUUID();

    var process = manager.startProcess(sessionId, List.of("sleep", "30"), tempDir);

    assertThat(process).isNotNull();
    assertThat(process.isAlive()).isTrue();
    assertThat(manager.isRunning(sessionId)).isTrue();

    manager.stopProcess(sessionId);
  }

  @Test
  @DisplayName("shouldStopProcessGracefully")
  void shouldStopProcessGracefully() throws Exception {
    var sessionId = UUID.randomUUID();

    manager.startProcess(sessionId, List.of("sleep", "30"), tempDir);

    manager.stopProcess(sessionId);

    assertThat(manager.isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("shouldReportNotRunningForUnknownSession")
  void shouldReportNotRunningForUnknownSession() {
    assertThat(manager.isRunning(UUID.randomUUID())).isFalse();
  }

  @Test
  @DisplayName("shouldReportNotRunningAfterProcessExitsNaturally")
  void shouldReportNotRunningAfterProcessExitsNaturally() throws Exception {
    var sessionId = UUID.randomUUID();

    var process = manager.startProcess(sessionId, List.of("echo", "done"), tempDir);
    process.waitFor();

    Thread.sleep(100);

    assertThat(manager.isRunning(sessionId)).isFalse();
  }

  @Test
  @DisplayName("shouldHandleStopOnAlreadyStoppedSession")
  void shouldHandleStopOnAlreadyStoppedSession() {
    var sessionId = UUID.randomUUID();

    manager.stopProcess(sessionId);
  }
}
