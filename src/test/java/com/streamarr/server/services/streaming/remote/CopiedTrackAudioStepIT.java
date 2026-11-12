package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.AudioSteps;
import com.streamarr.server.domain.streaming.AudioTrackChannels;
import com.streamarr.server.domain.streaming.ChannelLayout;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.MediaSegmentTimeline;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.TranscodeDecisionRequest;
import com.streamarr.server.services.streaming.TranscodeDecisionService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.local.SegmentFolderLock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The lower audio step of a copied 3- or 4-channel track, encoded by the pinned worker image and
 * decoded by its FFmpeg (ADR 0050).
 */
@Tag("IntegrationTest")
@DisplayName("Copied Track Audio Step Integration Tests")
class CopiedTrackAudioStepIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
  private static final Duration SEGMENT_DURATION = Duration.ofSeconds(6);
  // A lossy encode at the step's bitrate keeps each channel's pink noise within this many decibels
  // of the copied channel's level, while a channel that the encode mixed with another or left out
  // moves by 3 dB or more.
  private static final double CHANNEL_LEVEL_TOLERANCE_DB = 1.0;

  @TempDir static Path tempDir;

  private static Path mediaRoot;
  private static LocalSegmentStore segmentStore;
  private static WorkerSessionServer server;
  private static WorkerContainerFixture worker;

  @BeforeAll
  static void startServerAndWorker() throws Exception {
    mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    segmentStore =
        new LocalSegmentStore(tempDir.resolve("server-segments"), new SegmentFolderLock());
    server =
        WorkerSessionServer.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(serverConfigurationBuilder().address("127.0.0.1").build())
            .segmentStore(segmentStore)
            .meterRegistry(new SimpleMeterRegistry())
            .build();
    server.start();
    worker =
        WorkerContainerFixture.builder()
            .workerSessions(server)
            .sourceNamespaceId(SOURCE_NAMESPACE_ID)
            .sourceRoot(mediaRoot)
            .build();
    worker.start();
  }

  @AfterAll
  static void stopServerAndWorker() {
    if (worker != null) {
      worker.close();
    }

    if (server != null) {
      server.close();
    }
  }

  // AAC carries 3.0 and 4.0, and AC-3 and E-AC-3 carry 2.1, 3.0, 4.0 and their one 2/2 mode, which
  // their decoders report as quad(side) (ADR 0050). The step keeps the copy's codec and layout, so
  // the decoded step names the copy's layout and keeps every channel at the copy's level.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesThatKeepTheirLayoutInTheirOwnCodecsStep")
  @DisplayName(
      "Should decode the copied codec's step in the copy's layout with every channel at the copy's"
          + " level when the codec carries a copied 3- or 4-channel layout exactly")
  void
      shouldDecodeTheCopiedCodecsStepInTheCopysLayoutWithEveryChannelAtTheCopysLevelWhenTheCodecCarriesACopied3Or4ChannelLayoutExactly(
          CopiedTrack copied) throws Exception {
    var source = generateSource(copied);
    var probe = probe(source);
    var declaration =
        defaultDeclarationBuilder()
            .audio(
                List.of(
                    aacEntryBuilder().maxChannels(6).build(),
                    audioEntryBuilder(copied.codec()).maxChannels(6).build()))
            .build();
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var copy =
        new TranscodeDecisionService(
                StreamingProperties.builder().targetSegmentDuration(SEGMENT_DURATION).build(),
                new SimpleMeterRegistry())
            .decide(
                TranscodeDecisionRequest.builder()
                    .source(probe)
                    .capabilityDeclaration(declaration)
                    .encodableVideoCodecFamilies(executor.encodableVideoCodecFamilies())
                    .build())
            .orElseThrow();
    var step =
        AudioSteps.below(copy.audioDecision(), AudioTrackChannels.of(probe), declaration)
            .highestFirst()
            .get(1);

    assertThat(copy.transcodeMode()).isEqualTo(TranscodeMode.REMUX);
    assertThat(step)
        .extracting(AudioDecision::mode, AudioDecision::codec, AudioDecision::bitrate)
        .containsExactly(
            AudioMode.TRANSCODE, copied.codec().probedName(), copied.expectedStepBitrate());
    assertThat(step.channelLayout().map(ChannelLayout::getFfmpegName)).contains(copied.layout());
    var output = encode(probe, source, copy.withAudioDecision(step));
    var copiedAudio = worker.decodedAudio(source);
    var decodedStep = worker.decodedAudio(output);
    assertThat(copiedAudio.channelLayout()).as("the copy's layout").isEqualTo(copied.layout());
    assertThat(decodedStep.channelLayout())
        .as("the decoded step's layout")
        .isEqualTo(copied.layout());
    assertThat(decodedStep.channelLevels())
        .as("each decoded channel's RMS level beside the copy's")
        .hasSize(copied.channels())
        .hasSameSizeAs(copiedAudio.channelLevels());
    IntStream.range(0, copied.channels())
        .forEach(
            channel ->
                assertThat(decodedStep.channelLevels().get(channel))
                    .as("channel %d's level in dBFS", channel + 1)
                    .isCloseTo(
                        copiedAudio.channelLevels().get(channel),
                        within(CHANNEL_LEVEL_TOLERANCE_DB)));
  }

  static Stream<CopiedTrack> copiesThatKeepTheirLayoutInTheirOwnCodecsStep() {
    var aac = CopiedTrack.builder().codec(AudioCodec.AAC);
    var ac3 =
        CopiedTrack.builder().codec(AudioCodec.AC3).bitrate("640k").expectedStepBitrate(448_000L);
    var eac3 =
        CopiedTrack.builder().codec(AudioCodec.EAC3).bitrate("768k").expectedStepBitrate(640_000L);
    var cases = new ArrayList<CopiedTrack>();
    cases.add(aac.layout("3.0").channels(3).bitrate("320k").expectedStepBitrate(192_000L).build());
    cases.add(aac.layout("4.0").channels(4).bitrate("384k").expectedStepBitrate(256_000L).build());
    for (var codec : List.of(ac3, eac3)) {
      cases.add(codec.layout("2.1").channels(3).build());
      cases.add(codec.layout("3.0").channels(3).build());
      cases.add(codec.layout("4.0").channels(4).build());
      cases.add(codec.layout("quad(side)").channels(4).build());
    }

    return cases.stream();
  }

  // Independent pink noise in each channel of the layout, beside a small H.264 picture that the
  // default declaration copies.
  private static Path generateSource(CopiedTrack copied) throws Exception {
    var channels = new StringBuilder();
    var inputs = new StringBuilder();
    for (var channel = 0; channel < copied.channels(); channel++) {
      channels.append(
          "anoisesrc=color=pink:amplitude=0.25:duration=4:seed=%d[a%d];"
              .formatted(channel + 1, channel));
      inputs.append("[a%d]".formatted(channel));
    }

    return worker.generateMedia(
        mediaRoot.resolve(UUID.randomUUID() + ".mp4"),
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=64x36:rate=24:duration=4",
            "-filter_complex",
            "%s%sjoin=inputs=%d:channel_layout=%s[a]"
                .formatted(channels, inputs, copied.channels(), copied.layout()),
            "-map",
            "0:v",
            "-map",
            "[a]",
            "-c:v",
            "libx264",
            "-pix_fmt",
            "yuv420p",
            "-g",
            "24",
            "-c:a",
            copied.codec().probedName(),
            "-b:a",
            copied.bitrate()));
  }

  private static MediaProbe probe(Path source) {
    var request =
        ProbeExecutionRequest.builder()
            .sourcePath(source)
            .attemptId(UUID.randomUUID())
            .probeVersion(ProbeVersion.CURRENT)
            .priority(ProbePriority.BACKGROUND)
            .build();
    return switch (new RemoteFfprobeService(server, SOURCE_NAMESPACE_ID, mediaRoot)
        .probe(request)) {
      case ProbeOutcome.Success success -> success.mediaProbe();
      case ProbeOutcome.Failure failure -> throw new AssertionError(failure.toString());
    };
  }

  // Runs the decision on the worker and joins its initialization segment and media segments.
  private static Path encode(MediaProbe probe, Path source, TranscodeDecision decision)
      throws Exception {
    var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
    var sessionId = UUID.randomUUID();
    var timeline = new MediaSegmentTimeline.PlainGrid(probe.duration(), SEGMENT_DURATION);
    executor.start(
        TranscodeRequest.builder()
            .sessionId(sessionId)
            .sourcePath(source)
            .targetSegmentDuration(timeline.targetSegmentDurationSeconds())
            .mediaSegmentCount(timeline.mediaSegmentCount())
            .framerate(probe.framerate())
            .transcodeDecision(decision)
            .width(probe.width())
            .height(probe.height())
            .bitrate(probe.bitrate())
            .encoderPreset(EncoderPreset.FASTER)
            .build());
    await()
        .atMost(Duration.ofSeconds(60))
        .until(() -> !executor.isRunning(sessionId, StreamSession.defaultVariant()));
    var media = new ByteArrayOutputStream();
    media.writeBytes(segmentStore.readSegment(sessionId, SegmentNames.INITIALIZATION_SEGMENT_NAME));
    for (var index = 0; index < timeline.mediaSegmentCount(); index++) {
      media.writeBytes(segmentStore.readSegment(sessionId, SegmentNames.mediaSegmentName(index)));
    }

    return Files.write(tempDir.resolve(sessionId + ".mp4"), media.toByteArray());
  }

  // A copied track in the codec and layout, at the bitrate that FFmpeg encodes it at. The copied
  // codec's first lower step has expectedStepBitrate.
  @Builder
  record CopiedTrack(
      AudioCodec codec, String layout, int channels, String bitrate, long expectedStepBitrate) {

    @Override
    public String toString() {
      return codec.probedName() + " " + layout;
    }
  }
}
