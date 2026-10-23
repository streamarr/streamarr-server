package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Remote Ffprobe Service Tests")
class RemoteFfprobeServiceTest {

  @TempDir Path tempDir;

  @ParameterizedTest
  @ValueSource(
      strings = {".", "../outside.mkv", "nested/../../outside.mkv", "../movies-extra/film.mkv"})
  @DisplayName("Should reject probing when the source is not a file within its namespace")
  void shouldRejectProbingWhenSourceIsNotFileWithinItsNamespace(String relativePath) {
    var sourceRoot = tempDir.resolve("movies");
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(sourceRoot.resolve(relativePath))
              .priority(ProbePriority.BACKGROUND)
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isExactlyInstanceOf(TranscodeException.class)
          .hasMessage("Media source is outside the configured source namespace");
    }
  }

  @ParameterizedTest
  @EnumSource(ProbePriority.class)
  @DisplayName("Should defer the probe without a failure when no worker is connected")
  void shouldDeferTheProbeWithoutAFailureWhenNoWorkerIsConnected(ProbePriority priority)
      throws IOException {
    var sourceRoot = Files.createDirectories(tempDir.resolve("movies"));
    try (var server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().build())
            .segmentStore(new FakeSegmentStore())
            .meterRegistry(new SimpleMeterRegistry())
            .build()) {
      server.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(Files.createFile(sourceRoot.resolve("film.mkv")))
              .priority(priority)
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isExactlyInstanceOf(ProbeWorkersBusyException.class)
          .hasMessage(ProbeRefusal.NO_CONNECTED_WORKER.description());
    }
  }
}
