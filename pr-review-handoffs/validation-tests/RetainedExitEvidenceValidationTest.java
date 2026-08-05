package com.streamarr.server.services.streaming.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSession;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Temporary code-review validation tests for PR #255 findings. These tests intentionally assert
 * that the suspected defects MANIFEST — a passing test here confirms the bug exists.
 */
@Tag("UnitTest")
@DisplayName("VALIDATION: retained exit evidence findings (PR #255)")
class RetainedExitEvidenceValidationTest {

  @TempDir Path tempDir;

  private final LocalFfmpegProcessManager manager = new LocalFfmpegProcessManager();

  @Test
  @DisplayName(
      "VALIDATION (leak): session teardown does not sweep retained exit evidence — entry outlives"
          + " the session")
  void validateRetainedExitSurvivesSessionTeardown() throws Exception {
    var sessionId = UUID.randomUUID();
    var attemptId = UUID.randomUUID();

    var process =
        manager.startProcess(
            sessionId,
            StreamSession.defaultVariant(),
            attemptId,
            List.of("bash", "-c", "exit 1"),
            tempDir);
    process.waitFor();
    Thread.sleep(200);
    assertThat(manager.isRunning(sessionId, StreamSession.defaultVariant())).isFalse();

    // Full session teardown: this is the path destroySession -> stopForDestroy -> stop takes.
    manager.stopProcess(sessionId);

    // If teardown swept evidence, this would be empty. It is present: the entry leaked and would
    // stay for the life of the JVM (only consumeExit for the exact attempt removes it).
    var leaked = manager.consumeExit(sessionId, StreamSession.defaultVariant(), attemptId);
    assertThat(leaked)
        .as("evidence retained after full session teardown proves the map entry leaks")
        .isPresent();
  }

  @Test
  @DisplayName(
      "VALIDATION (double consume): concurrent consumers can all receive the same retained"
          + " evidence")
  void validateConcurrentConsumersCanAllReceiveTheSameEvidence() throws Exception {
    var consumers = 8;
    var doubleConsumes = 0;

    try (var executor = Executors.newFixedThreadPool(consumers)) {
      for (var iteration = 0; iteration < 120 && doubleConsumes == 0; iteration++) {
        var sessionId = UUID.randomUUID();
        var attemptId = UUID.randomUUID();

        var process =
            manager.startProcess(
                sessionId, StreamSession.defaultVariant(), attemptId, List.of("true"), tempDir);
        process.waitFor();
        assertThat(manager.isRunning(sessionId, StreamSession.defaultVariant())).isFalse();

        var barrier = new CyclicBarrier(consumers + 1);
        var go = new AtomicBoolean(false);
        var consumed = new AtomicInteger();
        var tasks =
            java.util.stream.IntStream.range(0, consumers)
                .mapToObj(
                    _ ->
                        executor.submit(
                            () -> {
                              barrier.await();
                              while (!go.get()) {
                                Thread.onSpinWait();
                              }
                              manager
                                  .consumeExit(sessionId, StreamSession.defaultVariant(), attemptId)
                                  .ifPresent(_ -> consumed.incrementAndGet());
                              return null;
                            }))
                .toList();

        barrier.await(5, TimeUnit.SECONDS);
        go.set(true);
        for (var task : tasks) {
          task.get(5, TimeUnit.SECONDS);
        }

        if (consumed.get() > 1) {
          doubleConsumes = consumed.get();
        }
      }
    }

    assertThat(doubleConsumes)
        .as("more than one consumer received the same 'consume exactly once' evidence")
        .isGreaterThan(1);
  }
}
