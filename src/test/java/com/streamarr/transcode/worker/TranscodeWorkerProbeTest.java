package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Duration;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.protocol.ProtoUuid;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Tests")
class TranscodeWorkerProbeTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should preserve failure correlation when a worker has no probe producer")
  void shouldPreserveFailureCorrelationWhenAWorkerHasNoProbeProducer() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setProbeVersion(17).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();

      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION));
    }
  }

  @Test
  @DisplayName("Should omit probe support when connecting a transcode-only worker")
  void shouldOmitProbeSupportWhenConnectingATranscodeOnlyWorker() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);

      assertThat(runtime.connection().registration().getCapabilities().getProbeVersionsList())
          .isEmpty();
    }
  }

  @Test
  @DisplayName("Should reject a probe source when a symlink escapes its namespace")
  void shouldRejectAProbeSourceWhenASymlinkEscapesItsNamespace() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("media"));
    var outside =
        Files.writeString(
            tempDir.resolve("outside.mkv"),
            """
        {"streams":[{"index":0,"codec_type":"video"}]}
        """);
    Files.createSymbolicLink(root.resolve("escape.mkv"), outside);
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("escape.mkv")).build();
    var runtime = new ScriptedWorkerRuntime();

    try (var worker =
        workerBuilder(root).runtime(runtime).ffprobe(fileContentsProducer()).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE));
    }
  }

  @Test
  @DisplayName("Should return a source failure when the mapped media file is missing")
  void shouldReturnASourceFailureWhenTheMappedMediaFileIsMissing() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("missing.mkv")).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE));
    }
  }

  @ParameterizedTest
  @CsvSource({"1, PROBE_FAILURE_INVALID_MEDIA", "2, PROBE_FAILURE_UNSUPPORTED_VERSION"})
  @DisplayName("Should retain typed probe failures when the worker rejects media or a version")
  void shouldRetainTypedProbeFailuresWhenTheWorkerRejectsMediaOrAVersion(
      int version, ProbeFailure expectedFailure) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setProbeVersion(version).build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ ->
                processBuilder()
                    .stdout(
                        """
            {"error":{"code":-1094995529}}
            """)
                    .exitCode(1)
                    .build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results()).containsExactly(failure(request, expectedFailure));
    }
  }

  @Test
  @DisplayName("Should return cancellation when a queued probe is cancelled before execution")
  void shouldReturnCancellationWhenAQueuedProbeIsCancelledBeforeExecution() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);

      cancel(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
    }
  }

  @ParameterizedTest
  @EnumSource(IdentityMismatch.class)
  @DisplayName("Should ignore a probe start when it targets another worker or boot")
  void shouldIgnoreAProbeStartWhenItTargetsAnotherWorkerOrBoot(IdentityMismatch mismatch)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var ignored = requestBuilder().build();
    var accepted = requestBuilder().build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.deliver(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartProbe(
                  StartProbeCommand.newBuilder()
                      .setTarget(mismatch.change(connection.registration().getWorker()))
                      .setRequest(ignored))
              .build());
      start(connection, accepted);

      runtime.probes().drain();

      assertThat(connection.results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(accepted.getProbeAttemptId());
                assertThat(result.getProbeVersion()).isEqualTo(accepted.getProbeVersion());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }

  private enum IdentityMismatch {
    WORKER,
    BOOT;

    private WorkerIdentity change(WorkerIdentity identity) {
      var otherId = ProtoUuid.toProto(UUID.randomUUID());
      return switch (this) {
        case WORKER -> identity.toBuilder().setWorkerId(otherId).build();
        case BOOT -> identity.toBuilder().setBootId(otherId).build();
      };
    }
  }

  @ParameterizedTest
  @EnumSource(SessionEnding.class)
  @DisplayName("Should terminate an active probe when the control connection ends")
  void shouldTerminateAnActiveProbeWhenTheControlConnectionEnds(SessionEnding ending)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        var running = runtime.probes().runNext();
        process.awaitStarted();

        ending.end(connection);

        process.awaitTerminationRequested();
        running.get(5, TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();
      } finally {
        process.finish();
      }
    }
  }

  private enum SessionEnding {
    COMPLETED,
    FAILED;

    private void end(ScriptedWorkerRuntime.Connection connection) {
      Consumer<ScriptedWorkerRuntime.Connection> action =
          switch (this) {
            case COMPLETED -> ScriptedWorkerRuntime.Connection::complete;
            case FAILED -> value -> value.fail("old connection failed");
          };
      action.accept(connection);
    }
  }

  @ParameterizedTest
  @EnumSource(SessionEnding.class)
  @DisplayName("Should preserve replacement work and disconnection when an old callback arrives")
  void shouldPreserveReplacementWorkAndDisconnectionWhenAnOldCallbackArrives(SessionEnding ending)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var processes = new FakeFfmpegProcessManager();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var job = variantJobBuilder().build();
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .engine(remuxEngine(processes))
            .runtime(runtime)
            .ffprobe(producer)
            .build()) {
      try {
        worker.start("localhost", 1);
        var old = runtime.connection();
        worker.close();
        worker.start("localhost", 1);
        var replacement = runtime.connection();
        replacement.deliver(
            EstablishWorkerSessionResponse.newBuilder()
                .setStartVariant(
                    StartVariantCommand.newBuilder()
                        .setTarget(replacement.registration().getWorker())
                        .setJob(job))
                .build());
        start(replacement, request);
        var running = runtime.probes().runNext();
        process.awaitStarted();

        ending.end(old);

        assertThat(processes.isRunning(fromProto(job.getStreamSessionId()), "original")).isTrue();
        process.finish();
        running.get(5, TimeUnit.SECONDS);
        assertThat(replacement.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });

        replacement.fail("replacement connection ended");
        assertThatThrownBy(worker::awaitDisconnection)
            .isInstanceOf(WorkerJobException.class)
            .hasRootCauseMessage("UNAVAILABLE: replacement connection ended");
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should await probe termination outside the worker monitor when closing")
  void shouldAwaitProbeTerminationOutsideTheWorkerMonitorWhenClosing() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).deferredTermination(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        worker.start("localhost", 1);
        start(runtime.connection(), requestBuilder().build());
        var probes = runtime.probes();
        var running = probes.runNext();
        process.awaitStarted();

        var closing = tasks.submit(worker::close);
        try {
          probes.awaitClosing();
          process.awaitTerminationRequested();
          assertThat(closing.isDone()).as("close is waiting for its live probe task").isFalse();
        } finally {
          process.finish();
        }

        closing.get(5, TimeUnit.SECONDS);
        running.get(5, TimeUnit.SECONDS);
        assertThat(process.isAlive()).isFalse();
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should discard an old probe result when a replacement connection is active")
  void shouldDiscardAnOldProbeResultWhenAReplacementConnectionIsActive() throws Exception {
    var oldSource = Files.writeString(tempDir.resolve("old.mkv"), "old media").toRealPath();
    Files.writeString(tempDir.resolve("movie.mkv"), "new media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).deferredTermination(true).build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            source -> source.equals(oldSource) ? process : processBuilder().build());
    var oldRequest = requestBuilder().setSource(sourceBuilder().setRelativeKey("old.mkv")).build();
    var nextRequest = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        worker.start("localhost", 1);
        var old = runtime.connection();
        var oldProbes = runtime.probes();
        start(old, oldRequest);
        oldProbes.runNext();
        process.awaitStarted();
        var closing = tasks.submit(worker::close);
        oldProbes.awaitClosing();

        worker.start("localhost", 1);
        var replacement = runtime.connection();
        process.finish();
        closing.get(5, TimeUnit.SECONDS);
        start(replacement, nextRequest);
        runtime.probes().drain();

        assertThat(old.results()).isEmpty();
        assertThat(replacement.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(nextRequest.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should ignore a queued old command when its worker connection has closed")
  void shouldIgnoreAQueuedOldCommandWhenItsWorkerConnectionHasClosed() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());
    var ignored = requestBuilder().build();
    var accepted = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var old = runtime.connection();
      worker.close();
      worker.start("localhost", 1);
      var replacement = runtime.connection();

      start(old, ignored);
      start(replacement, accepted);
      runtime.probes().drain();

      assertThat(old.results()).isEmpty();
      assertThat(replacement.results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(accepted.getProbeAttemptId());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should remain usable when cancelling an unknown or completed probe attempt")
  void shouldRemainUsableWhenCancellingAnUnknownOrCompletedProbeAttempt(boolean completed)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());
    var absent = requestBuilder().build();
    var accepted = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      if (completed) {
        start(connection, absent);
        runtime.probes().drain();
      }

      cancel(connection, absent);
      start(connection, accepted);
      runtime.probes().drain();

      assertThat(connection.results())
          .filteredOn(result -> result.getProbeAttemptId().equals(accepted.getProbeAttemptId()))
          .singleElement()
          .satisfies(result -> assertThat(result.hasMedia()).isTrue());
    }
  }

  @ParameterizedTest
  @EnumSource(IdentityMismatch.class)
  @DisplayName("Should preserve a probe when cancellation targets another worker or boot")
  void shouldPreserveAProbeWhenCancellationTargetsAnotherWorkerOrBoot(IdentityMismatch mismatch)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var running = runtime.probes().runNext();
        process.awaitStarted();

        connection.deliver(
            EstablishWorkerSessionResponse.newBuilder()
                .setCancelProbe(
                    CancelProbeCommand.newBuilder()
                        .setTarget(mismatch.change(connection.registration().getWorker()))
                        .setProbeAttemptId(request.getProbeAttemptId()))
                .build());
        process.finish();
        running.get(5, TimeUnit.SECONDS);

        assertThat(connection.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should preserve the complete typed result when probing a literal source key")
  void shouldPreserveTheCompleteTypedResultWhenProbingALiteralSourceKey() throws Exception {
    Files.writeString(
        tempDir.resolve("Film %20.mkv"),
        """
        {"format":{"format_name":"matroska","duration":"1.25","bit_rate":"3500000"},
         "streams":[
          {"index":4,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
           "r_frame_rate":"24000/1001","bit_rate":"3000000"},
          {"index":7,"codec_type":"audio","codec_name":"aac","channels":6,"bit_rate":"384000",
           "tags":{"language":"eng"},"disposition":{"default":1}},
          {"index":9,"codec_type":"subtitle","codec_name":"subrip",
           "tags":{"language":"fra"},"disposition":{"forced":1}}]}
        """);
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder().setSource(sourceBuilder().setRelativeKey("Film %20.mkv")).build();
    var expected =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .setContainer(
                        ProbeContainerInfo.newBuilder()
                            .setFormat("matroska")
                            .setDuration(Duration.newBuilder().setSeconds(1).setNanos(250_000_000))
                            .setBitrateBitsPerSecond(3_500_000))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(4)
                            .setCodecType("video")
                            .setCodec("h264")
                            .setWidth(1920)
                            .setHeight(1080)
                            .setFramerate(24000.0 / 1001)
                            .setBitrateBitsPerSecond(3_000_000))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(7)
                            .setCodecType("audio")
                            .setCodec("aac")
                            .setChannels(6)
                            .setBitrateBitsPerSecond(384_000)
                            .setLanguage("eng")
                            .setIsDefault(true))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(9)
                            .setCodecType("subtitle")
                            .setCodec("subrip")
                            .setLanguage("fra")
                            .setIsForced(true)))
            .build();

    try (var worker =
        workerBuilder(tempDir).runtime(runtime).ffprobe(fileContentsProducer()).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results()).containsExactly(expected);
    }
  }
}
