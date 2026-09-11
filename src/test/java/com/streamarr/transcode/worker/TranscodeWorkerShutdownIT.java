package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import io.grpc.protobuf.services.HealthStatusManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Shutdown Tests")
class TranscodeWorkerShutdownIT {

  @TempDir Path directory;

  @Test
  @DisplayName("Should close cleanly without warning stack traces when shutdown is repeated")
  void shouldCloseCleanlyWithoutWarningStackTracesWhenShutdownIsRepeated() throws Exception {
    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker =
            new TranscodeWorker(
                workerConfigurationBuilder()
                    .healthPort(0)
                    .availableSlots(1)
                    .sourceNamespaces(Map.of(UUID.randomUUID(), directory))
                    .segmentBasePath(directory)
                    .build(),
                remuxEngine(new FakeFfmpegProcessManager()))) {
      server.start();
      worker.start("localhost", server.port());

      try (var logs = healthLogsForCurrentThread()) {
        worker.close();
        worker.close();

        assertThat(logs.warningFailures())
            .as("Repeated worker shutdown must not report a failed health-service shutdown")
            .isEmpty();
      }
    }
  }

  private CurrentThreadHealthLogs healthLogsForCurrentThread() {
    return CurrentThreadHealthLogs.builder()
        .logger(Logger.getLogger(HealthStatusManager.class.getPackageName()))
        .threadId(Thread.currentThread().threadId())
        .build();
  }

  private static final class CurrentThreadHealthLogs extends Handler implements AutoCloseable {

    private final Logger logger;
    private final long threadId;
    private final ConcurrentLinkedQueue<LogRecord> records = new ConcurrentLinkedQueue<>();

    @Builder
    private CurrentThreadHealthLogs(@NonNull Logger logger, long threadId) {
      this.logger = logger;
      this.threadId = threadId;
      logger.addHandler(this);
    }

    List<Throwable> warningFailures() {
      return records.stream()
          .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
          .map(LogRecord::getThrown)
          .filter(throwable -> throwable != null)
          .toList();
    }

    @Override
    public void publish(LogRecord record) {
      if (record.getLongThreadID() != threadId) {
        return;
      }

      records.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      logger.removeHandler(this);
    }
  }
}
