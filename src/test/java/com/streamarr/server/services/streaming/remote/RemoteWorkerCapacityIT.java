package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Remote Worker Capacity Integration Tests")
class RemoteWorkerCapacityIT {
  @TempDir Path mediaRoot;

  @Test
  @DisplayName(
      "Should retain the execution slot when a cancelled probe has not stopped in the worker")
  void shouldRetainTheExecutionSlotWhenACancelledProbeHasNotStoppedInTheWorker() throws Exception {
    Files.writeString(mediaRoot.resolve("held.mkv"), "held media");
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.copy(Path.of(source.toURI()), mediaRoot.resolve("next.mp4"));
    var request = request("held.mkv");
    var nextRequest = request("next.mp4");

    try (var server =
            new WorkerSessionServer(
                serverConfigurationBuilder().address("127.0.0.1").build(), new FakeSegmentStore());
        var worker =
            WorkerContainerFixture.builder()
                .workerSessions(server)
                .sourceNamespaceId(SOURCE_NAMESPACE_ID)
                .sourceRoot(mediaRoot)
                .ffprobeScript(
                    """
                for argument in "$@"; do
                  if [[ $argument == /media/held.mkv ]]; then
                    printf '%s' "$$" > /tmp/held-probe.tmp
                    mv /tmp/held-probe.tmp /tmp/held-probe
                    exec sleep 300
                  fi
                done
                """)
                .build()) {
      server.start();
      worker.start();
      var pending = dispatched(server.dispatchProbe(request));
      await().atMost(5, TimeUnit.SECONDS).until(worker::probeRunning);
      // Freeze the real producer and worker before cancellation can acknowledge its termination.
      worker.pause();
      try {
        assertThat(pending.cancel(true)).isTrue();
        assertThat(server.dispatchProbe(nextRequest)).isInstanceOf(ProbeDispatch.Refused.class);
      } finally {
        worker.unpause();
      }

      var next =
          dispatched(
              await()
                  .atMost(10, TimeUnit.SECONDS)
                  .until(
                      () -> server.dispatchProbe(nextRequest),
                      ProbeDispatch.Dispatched.class::isInstance));
      var result = next.get(10, TimeUnit.SECONDS);

      assertThat(worker.probeRunning()).isFalse();
      assertThat(result.getProbeAttemptId()).isEqualTo(nextRequest.getProbeAttemptId());
      assertThat(result.hasMedia()).isTrue();
      assertThat(result.getMedia().getStreamsList())
          .anySatisfy(stream -> assertThat(stream.getCodec()).isEqualTo("h264"));
    }
  }

  private ProbeRequest request(String relativeKey) {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(ProbeVersion.CURRENT)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey(relativeKey))
        .build();
  }
}
