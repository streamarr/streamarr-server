package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.audioEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioCodec;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.AudioMode;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.fixtures.ProbeFixture;
import com.streamarr.server.fixtures.StreamMeasurementFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audio step that a video copy takes below a copied track (ADR 0050, ADR 0054). The source is a
 * 2-hour file whose video the client plays as a copy, with one 5.1 FLAC track that the client also
 * plays. The probe knows no bitrate for the track and the server measured it at 4 Mb/s, beside a
 * video of 4.5 Mb/s.
 */
@Tag("UnitTest")
@DisplayName("Copied Video Copied Audio Step Tests")
class CopiedVideoCopiedAudioStepTest {

  private static final int VIDEO_STREAM = 0;
  private static final int AUDIO_STREAM = 1;

  // The copy of both streams costs about 8.5 Mb/s. The server's AC-3 5.1 encode at 448 kb/s beside
  // the copied video costs under 5 Mb/s, and it is the highest step below the copied track.
  @Test
  @DisplayName(
      "Should step a copied track to the highest lower step that fits when its measured average"
          + " exceeds the maximum bitrate beside a video copy and the probe knows no audio bitrate")
  void
      shouldStepACopiedTrackToTheHighestLowerStepThatFitsWhenItsMeasuredAverageExceedsTheMaximum() {
    var decision = decide(ViewerLimits.builder().maxBitrate(OptionalLong.of(5_200_000L)).build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.audioDecision())
        .extracting(
            AudioDecision::mode,
            AudioDecision::codec,
            AudioDecision::channels,
            AudioDecision::bitrate)
        .containsExactly(AudioMode.TRANSCODE, "ac3", 6, 448_000L);
  }

  @Test
  @DisplayName(
      "Should keep the copied track without naming the bitrate limit when the copy fits the"
          + " maximum bitrate at its measured average")
  void shouldKeepTheCopiedTrackWithoutNamingTheBitrateLimitWhenTheCopyFitsTheMaximum() {
    var decision = decide(ViewerLimits.builder().maxBitrate(OptionalLong.of(9_000_000L)).build());

    assertThat(decision.transcodeMode().copiesVideo()).isTrue();
    assertThat(decision.audioDecision())
        .extracting(AudioDecision::mode, AudioDecision::codec)
        .containsExactly(AudioMode.COPY, "flac");
    assertThat(decision.reasons()).doesNotContain(DecisionReason.AUDIO_LOWERED_FOR_BITRATE_LIMIT);
  }

  private static TranscodeDecision decide(ViewerLimits limits) {
    return new TranscodeDecisionService(streamingProperties(), new SimpleMeterRegistry())
        .decide(
            TranscodeDecisionRequest.builder()
                .source(measuredSourceWithUnprobedSurroundFlac())
                .capabilityDeclaration(declaringSurroundFlac())
                .encodableVideoCodecFamilies(Set.of("h264"))
                .viewerLimits(limits)
                .build())
        .orElseThrow();
  }

  private static MediaProbe measuredSourceWithUnprobedSurroundFlac() {
    var probe = defaultProbeBuilder().audioCodec("flac");
    var streams =
        List.of(
            ProbeFixture.videoStreamBuilder(probe.build()).index(VIDEO_STREAM).build(),
            ProbeFixture.audioStreamBuilder("flac")
                .index(AUDIO_STREAM)
                .channels(OptionalInt.of(6))
                .bitrate(OptionalLong.empty())
                .build());
    return probe
        .audioChannels(OptionalInt.of(6))
        .audioBitrate(OptionalLong.empty())
        .streams(streams)
        .streamMeasurements(Optional.of(measurement()))
        .build();
  }

  private static StreamMeasurements measurement() {
    return new StreamMeasurements(
        StreamMeasurementSource.BITRATE_SCAN,
        List.of(
            StreamMeasurementFixture.measuredStreamBuilder(VIDEO_STREAM, 4_050_000_000L)
                .packetCount(172_800)
                .measuredDuration(7_200_000)
                .build(),
            StreamMeasurementFixture.measuredStreamBuilder(AUDIO_STREAM, 3_600_000_000L)
                .packetCount(84_375L)
                .measuredDuration(7_200_000)
                .build()));
  }

  private static ClientCapabilityDeclaration declaringSurroundFlac() {
    return defaultDeclarationBuilder()
        .audio(
            List.of(
                aacEntryBuilder().build(),
                audioEntryBuilder(AudioCodec.FLAC).maxChannels(6).build(),
                audioEntryBuilder(AudioCodec.AC3).maxChannels(6).build()))
        .build();
  }

  private static StreamingProperties streamingProperties() {
    return StreamingProperties.builder()
        .maxConcurrentTranscodes(3)
        .targetSegmentDuration(Duration.ofSeconds(6))
        .sessionTimeout(Duration.ofSeconds(60))
        .build();
  }
}
