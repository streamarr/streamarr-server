package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Worker probe session integration")
class TranscodeWorkerProbeIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should wait for ffprobe termination when closing a probe worker")
  void shouldWaitForFfprobeTerminationWhenClosingAProbeWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "media");
    var process = new HeldProbeProcess();
    var ffprobe = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker = worker(mediaRoot, ffprobe);
        var closingTasks = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatchProbe(probeRequest("movie.mkv").build())).isPresent();
      assertThat(process.started.await(5, TimeUnit.SECONDS)).isTrue();

      var closing = closingTasks.submit(worker::close);
      try {
        assertThat(process.terminationRequested.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(closing.isDone()).isFalse();
      } finally {
        process.releaseTermination();
      }

      closing.get(5, TimeUnit.SECONDS);
      assertThat(process.isAlive()).isFalse();
    }
  }

  @Test
  @DisplayName("Should preserve the replacement connection when restarting a probe worker")
  void shouldPreserveTheReplacementConnectionWhenRestartingAProbeWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(
        mediaRoot.resolve("movie.mkv"),
        """
        {"streams":[{"index":0,"codec_type":"video"}]}
        """);

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker = worker(mediaRoot, FfprobeExecutor.forBinary(scriptedFfprobe()));
        var waiters = Executors.newVirtualThreadPerTaskExecutor()) {
      server.start();
      worker.start("localhost", server.port());

      for (var restart = 0; restart < 10; restart++) {
        worker.close();
        worker.start("localhost", server.port());
        var disconnected =
            waiters.submit(
                () -> {
                  worker.awaitDisconnection();
                  return true;
                });

        try {
          var result =
              server
                  .dispatchProbe(probeRequest("movie.mkv").build())
                  .orElseThrow()
                  .get(5, TimeUnit.SECONDS);
          assertThat(result.hasMedia()).isTrue();
          assertThatThrownBy(() -> disconnected.get(100, TimeUnit.MILLISECONDS))
              .isInstanceOf(TimeoutException.class);
        } finally {
          disconnected.cancel(true);
        }
      }
    }
  }

  @Test
  @DisplayName("Should retain the execution slot until ffprobe exits when cancelling a probe")
  void shouldRetainTheExecutionSlotUntilFfprobeExitsWhenCancellingAProbe() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "media");
    var process = new HeldProbeProcess();
    var ffprobe = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = probeRequest("movie.mkv").build();

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker = worker(mediaRoot, ffprobe)) {
      server.start();
      worker.start("localhost", server.port());
      var pending = server.dispatchProbe(request).orElseThrow();

      try {
        assertThat(process.started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(pending.cancel(true)).isTrue();

        assertThat(process.terminationRequested.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isTrue();
        assertThat(server.dispatchProbe(probeRequest("movie.mkv").build())).isEmpty();
      } finally {
        process.releaseTermination();
      }

      var nextRequest = probeRequest("movie.mkv").build();
      var next =
          await()
              .atMost(5, TimeUnit.SECONDS)
              .until(() -> server.dispatchProbe(nextRequest), Optional::isPresent)
              .orElseThrow();
      var result = next.get(5, TimeUnit.SECONDS);

      assertThat(process.isAlive()).isFalse();
      assertThat(result.getProbeAttemptId()).isEqualTo(nextRequest.getProbeAttemptId());
      assertThat(result.hasMedia()).isTrue();
    }
  }

  @Test
  @DisplayName("Should omit probe support when connecting a transcode-only worker")
  void shouldOmitProbeSupportWhenConnectingATranscodeOnlyWorker() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker =
            new TranscodeWorker(
                configuration(mediaRoot), remuxEngine(new FakeFfmpegProcessManager()))) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isTrue();
      assertThat(server.dispatchProbe(probeRequest("movie.mkv").build())).isEmpty();
    }
  }

  @Test
  @DisplayName("Should return a source failure when the mapped media file is missing")
  void shouldReturnASourceFailureWhenTheMappedMediaFileIsMissing() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var request = probeRequest("missing.mkv").build();

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker = worker(mediaRoot, FfprobeExecutor.forBinary(scriptedFfprobe()))) {
      server.start();
      worker.start("localhost", server.port());

      var result = server.dispatchProbe(request).orElseThrow().get(5, TimeUnit.SECONDS);

      assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(result.getProbeVersion()).isEqualTo(request.getProbeVersion());
      assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
    }
  }

  @Test
  @DisplayName(
      "Should return the typed media result when probing a source through a worker session")
  void shouldReturnTheTypedMediaResultWhenProbingASourceThroughAWorkerSession() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(
        mediaRoot.resolve("Film %20.mkv"),
        """
        {
          "format": {"format_name":"matroska", "duration":"1.25", "bit_rate":"3500000"},
          "streams": [
            {"index":0, "codec_type":"video", "codec_name":"h264", "width":1920, "height":1080},
            {"index":1, "codec_type":"audio", "codec_name":"aac", "channels":2},
            {"index":2, "codec_type":"subtitle", "codec_name":"subrip", "tags":{"language":"eng"}}
          ]
        }
        """);
    var request = probeRequest("Film %20.mkv").build();

    try (var server =
            new WorkerSessionServer(serverConfigurationBuilder().build(), new FakeSegmentStore());
        var worker = worker(mediaRoot, FfprobeExecutor.forBinary(scriptedFfprobe()))) {
      server.start();
      worker.start("localhost", server.port());

      var result = server.dispatchProbe(request).orElseThrow().get(5, TimeUnit.SECONDS);

      assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(result.getProbeVersion()).isEqualTo(FfprobeExecutor.PROBE_VERSION);
      assertThat(result.hasMedia()).isTrue();
      assertThat(result.getMedia().getContainer().getFormat()).isEqualTo("matroska");
      assertThat(result.getMedia().getContainer().getDuration().getNanos()).isEqualTo(250_000_000);
      assertThat(result.getMedia().getStreamsList())
          .extracting(ProbeStreamInfo::getCodecType)
          .containsExactly("video", "audio", "subtitle");
      assertThat(result.getMedia().getStreams(0).getWidth()).isEqualTo(1920);
      assertThat(result.getMedia().getStreams(2).getLanguage()).isEqualTo("eng");
    }
  }

  private ProbeRequest.Builder probeRequest(String relativeKey) {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey(relativeKey));
  }

  private TranscodeWorker worker(Path mediaRoot, FfprobeExecutor ffprobe) throws Exception {
    return new TranscodeWorker(
        configuration(mediaRoot), remuxEngine(new FakeFfmpegProcessManager()), ffprobe);
  }

  private TranscodeWorkerConfiguration configuration(Path mediaRoot) throws Exception {
    return workerConfigurationBuilder()
        .availableSlots(1)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
        .segmentBasePath(tempDir.resolve("segments"))
        .build();
  }

  private Path scriptedFfprobe() throws Exception {
    var binary = tempDir.resolve("ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        for source do :; done
        cat "$source"
        """);
    assertThat(binary.toFile().setExecutable(true)).isTrue();
    return binary;
  }

  private static final class HeldProbeProcess extends Process {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch terminationRequested = new CountDownLatch(1);
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(
          "{\"streams\":[{\"index\":0,\"codec_type\":\"video\"}]}"
              .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      started.countDown();
      try {
        terminated.get();
        return 0;
      } catch (ExecutionException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public int exitValue() {
      if (!terminated.isDone()) {
        throw new IllegalThreadStateException("Probe process is still running");
      }

      return 0;
    }

    @Override
    public boolean isAlive() {
      return !terminated.isDone();
    }

    @Override
    public void destroy() {
      destroyForcibly();
    }

    @Override
    public Process destroyForcibly() {
      terminationRequested.countDown();
      terminated.join();
      return this;
    }

    private void releaseTermination() {
      terminated.complete(null);
    }
  }
}
