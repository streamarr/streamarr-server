package com.streamarr.transcode.worker.support;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.CancelProbeCommand;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StartProbeCommand;
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.worker.TranscodeWorker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class WorkerProbeFixtures {

  private WorkerProbeFixtures() {}

  public static TranscodeWorker.TranscodeWorkerBuilder workerBuilder(Path root) throws Exception {
    return TranscodeWorker.builder()
        .configuration(
            workerConfigurationBuilder()
                .plaintext(true)
                .tlsIdentity(Optional.empty())
                .availableSlots(2)
                .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, root))
                .segmentBasePath(root.resolve("segments"))
                .build())
        .engine(remuxEngine(new FakeFfmpegProcessManager()));
  }

  public static ProbeRequest.Builder requestBuilder() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(1)
        .setSource(sourceBuilder());
  }

  public static MediaSourceRef.Builder sourceBuilder() {
    return MediaSourceRef.newBuilder()
        .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
        .setRelativeKey("movie.mkv");
  }

  public static ControlledProbeProcess.ControlledProbeProcessBuilder processBuilder() {
    return ControlledProbeProcess.builder()
        .stdout(
            """
            {"streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}
            """);
  }

  public static ProbeAttemptResult failure(ProbeRequest request, ProbeFailure failure) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setFailure(failure)
        .build();
  }

  public static void start(ScriptedWorkerRuntime.Connection connection, ProbeRequest request)
      throws Exception {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setRequest(request))
            .build());
  }

  public static void cancel(ScriptedWorkerRuntime.Connection connection, ProbeRequest request)
      throws Exception {
    connection.deliver(
        EstablishWorkerSessionResponse.newBuilder()
            .setCancelProbe(
                CancelProbeCommand.newBuilder()
                    .setTarget(connection.registration().getWorker())
                    .setProbeAttemptId(request.getProbeAttemptId()))
            .build());
  }

  public static VariantJob.Builder variantJobBuilder() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(sourceBuilder())
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
                .setVideoCodecFamily("h264")
                .setAudio(AudioDecision.newBuilder().setMode(AudioMode.AUDIO_MODE_COPY))
                .setSubtitle(
                    SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                .setContainer(ContainerFormat.CONTAINER_FORMAT_MPEG_TS))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("original"))
        .setExecution(
            TranscodeExecution.newBuilder().setTargetSegmentDurationSeconds(6).setFramerate(24));
  }

  public static FfprobeExecutor fileContentsProducer() {
    return new FfprobeExecutor(
        new ObjectMapper(),
        source -> {
          try {
            return processBuilder().stdout(Files.readString(source)).build();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        });
  }
}
