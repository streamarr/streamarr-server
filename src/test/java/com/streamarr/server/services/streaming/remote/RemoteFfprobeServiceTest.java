package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeSegmentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
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
        new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore())) {
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(sourceRoot.resolve(relativePath))
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isExactlyInstanceOf(TranscodeException.class)
          .hasMessage("Media source is outside the configured source namespace");
    }
  }

  @Test
  @DisplayName("Should report a temporary failure when no worker is connected")
  void shouldReportTemporaryFailureWhenNoWorkerIsConnected() throws IOException {
    var sourceRoot = Files.createDirectories(tempDir.resolve("movies"));
    try (var server =
        new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore())) {
      server.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, sourceRoot);
      var request =
          ProbeExecutionRequest.builder()
              .attemptId(UUID.randomUUID())
              .probeVersion(ProbeVersion.CURRENT)
              .sourcePath(Files.createFile(sourceRoot.resolve("film.mkv")))
              .build();

      assertThatThrownBy(() -> service.probe(request))
          .isInstanceOfSatisfying(
              ProbeExecutionException.class,
              exception -> assertThat(exception.reason()).isEqualTo(ItemFailureReason.TEMPORARY))
          .hasMessage(ProbeRefusal.NO_CONNECTED_WORKER.description());
    }
  }
}
