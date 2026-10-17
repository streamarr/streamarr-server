package com.streamarr.server.fixtures.mesh;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
final class MeshMediaHandler implements HttpHandler {

  // The mesh fixture is the committed 10.005 s clip, advertised on a 2 s grid.
  private static final MediaSegmentTimeline FIXTURE_TIMELINE =
      new MediaSegmentTimeline.PlainGrid(Duration.ofMillis(10_005), Duration.ofSeconds(2));
  private static final Path FIXTURE_SOURCE_ROOT = Path.of("/media");

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
            .setProbeVersion(ProbeVersion.CURRENT)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(MeshValidationServer.SOURCE_ID))
                    .setRelativeKey("mesh-fixture.mkv"))
            .build();
    var result = dispatched(server.dispatchProbe(request)).get(10, TimeUnit.SECONDS).result();
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
    var executor =
        new RemoteTranscodeExecutor(server, MeshValidationServer.SOURCE_ID, FIXTURE_SOURCE_ROOT);
    executor.start(
        TranscodeRequest.builder()
            .sessionId(sessionId)
            .sourcePath(FIXTURE_SOURCE_ROOT.resolve("mesh-fixture.mkv"))
            .transcodeDecision(
                TranscodeDecision.builder()
                    .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                    .videoCodecFamily("h264")
                    .audioDecision(AudioDecision.stereoAac())
                    .subtitleDecision(SubtitleDecision.exclude())
                    .videoSource(
                        Optional.of(
                            VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                                .streamIndex(0)
                                .codecString(Optional.of("avc1.42C00D"))
                                .frameRateRule(
                                    VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                                .build()))
                    .build())
            .width(320)
            .height(180)
            .bitrate(400_000)
            .encoderPreset(EncoderPreset.VERYFAST)
            .targetSegmentDuration(FIXTURE_TIMELINE.targetSegmentDurationSeconds())
            .mediaSegmentCount(FIXTURE_TIMELINE.mediaSegmentCount())
            .framerate(OptionalDouble.of(24))
            .build());
    try {
      return segments.awaitFirstDecodableMedia(sessionId);
    } finally {
      executor.stop(sessionId);
      segments.deleteSession(sessionId);
    }
  }
}
