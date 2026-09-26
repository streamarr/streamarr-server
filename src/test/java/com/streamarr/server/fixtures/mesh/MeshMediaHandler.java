package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
final class MeshMediaHandler implements HttpHandler {

  // The mesh fixture is the committed 10.005 s clip, advertised on a 2 s grid.
  private static final MediaSegmentTimeline FIXTURE_TIMELINE =
      new MediaSegmentTimeline(Duration.ofMillis(10_005), Duration.ofSeconds(2));

  private final WorkerSessionServer server;
  private final MeshSegmentStore segments;

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    byte[] body;
    try {
      body = exchange.getRequestURI().getPath().equals("/media/segment") ? transcode() : probe();
    } catch (Exception failure) {
      log.warn("Mesh media exchange failed for {}", exchange.getRequestURI().getPath(), failure);
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
      return;
    }

    try (exchange) {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
  }

  private byte[] probe() throws Exception {
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(MeshValidationServer.SOURCE_ID))
                    .setRelativeKey("mesh-fixture.mkv"))
            .build();
    var result = dispatched(server.dispatchProbe(request)).get(10, TimeUnit.SECONDS);
    var media = result.getMedia();
    if (!Arrays.asList(media.getContainer().getFormat().split(",")).contains("mp4")
        || media.getStreamsList().stream().noneMatch(this::isFixtureVideo)) {
      throw new IllegalStateException("The worker did not probe the expected media fixture");
    }

    return "MEDIA_PROBE_COMPLETED".getBytes(StandardCharsets.UTF_8);
  }

  private boolean isFixtureVideo(ProbeStreamInfo stream) {
    return stream.getCodecType().equals("video")
        && stream.getCodec().equals("h264")
        && stream.getWidth() == 320
        && stream.getHeight() == 180;
  }

  private byte[] transcode() throws Exception {
    var sessionId = UUID.randomUUID();
    var job =
        VariantJob.newBuilder()
            .setStreamSessionId(toProto(sessionId))
            .setJobId(toProto(UUID.randomUUID()))
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(MeshValidationServer.SOURCE_ID))
                    .setRelativeKey("mesh-fixture.mkv"))
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                    .setVideoCodecFamily("h264")
                    .setAudio(
                        AudioDecision.newBuilder()
                            .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                            .setCodec("aac")
                            .setChannels(2)
                            .setBitrateBitsPerSecond(128_000))
                    .setSubtitle(
                        SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                    .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4)
                    .setAlignKeyframesToSegments(true))
            .setVariant(
                VariantSpec.newBuilder()
                    .setVariantLabel("default")
                    .setWidth(320)
                    .setHeight(180)
                    .setBitrateBitsPerSecond(400_000))
            .setExecution(
                TranscodeExecution.newBuilder()
                    .setTargetSegmentDurationSeconds(
                        FIXTURE_TIMELINE.targetSegmentDurationSeconds())
                    .setMediaSegmentCount(FIXTURE_TIMELINE.mediaSegmentCount())
                    .setFramerate(24))
            .build();
    if (!server.dispatch(job)) {
      throw new IllegalStateException("No worker accepted the media fixture transcode");
    }

    try {
      return segments.awaitFirstDecodableMedia(sessionId);
    } finally {
      server.stopStreamSession(sessionId);
      segments.deleteSession(sessionId);
    }
  }
}
