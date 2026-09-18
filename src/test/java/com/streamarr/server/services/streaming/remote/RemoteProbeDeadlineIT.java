package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Remote Probe Deadline")
class RemoteProbeDeadlineIT {
  @TempDir Path mediaRoot;

  @Test
  @DisplayName("Should stop a hung native probe and reuse the worker when its deadline expires")
  void shouldStopHungNativeProbeAndReuseWorkerWhenDeadlineExpires() throws Exception {
    Files.writeString(mediaRoot.resolve("held.mkv"), "held media");
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.copy(Path.of(source.toURI()), mediaRoot.resolve("next.mp4"));
    var configuration = serverConfigurationBuilder().probeTimeout(Duration.ofSeconds(2)).build();
    try (var server = new WorkerSessionServer(configuration, new FakeSegmentStore());
        var worker =
            WorkerContainerFixture.builder()
                .workerSessions(server)
                .sourceNamespaceId(SOURCE_NAMESPACE_ID)
                .sourceRoot(mediaRoot)
                .ffprobeScript(
                    """
                for argument in "$@"; do
                  if [[ $argument == /media/held.mkv ]]; then
                    printf '%s' "$$" > /tmp/held-probe
                    exec sleep 300
                  fi
                done
                """)
                .build()) {
      server.start();
      worker.start();
      var service = new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var heldRequest = request("held.mkv");

      assertThatThrownBy(() -> service.probe(heldRequest))
          .isInstanceOf(ProbeExecutionException.class)
          .hasRootCauseInstanceOf(TimeoutException.class);
      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                assertThat(worker.probeRunning()).isFalse();
                assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
              });

      var outcome = service.probe(request("next.mp4"));
      assertThat(outcome)
          .isInstanceOfSatisfying(
              ProbeOutcome.Success.class,
              success -> assertThat(success.mediaProbe().videoCodec()).isEqualTo("h264"));
    }
  }

  private ProbeExecutionRequest request(String filename) {
    return ProbeExecutionRequest.builder()
        .sourcePath(mediaRoot.resolve(filename))
        .attemptId(UUID.randomUUID())
        .probeVersion(ProbeVersion.CURRENT)
        .build();
  }
}
