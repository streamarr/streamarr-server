package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.FrameRateRule;
import com.streamarr.server.domain.streaming.FrameRateRuleInputs;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.OutputFrameRateBasis;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ProbeFixture {

  private static final Map<String, String> AUDIO_CODEC_STRINGS =
      Map.of(
          "aac", "mp4a.40.2",
          "ac3", "ac-3",
          "eac3", "ec-3",
          "flac", "fLaC",
          "opus", "Opus",
          "alac", "alac");

  public static StreamInfo.StreamInfoBuilder audioStreamBuilder(String codec) {
    return StreamInfo.builder()
        .codecType("audio")
        .codec(Optional.ofNullable(codec))
        .codecString(Optional.ofNullable(AUDIO_CODEC_STRINGS.get(codec)));
  }

  /**
   * A probe outcome whose {@link ProbeOutcome.Success#mediaProbe()} reads back the given probe. A
   * probe that names its chosen video stream reads back as a probe version 4 outcome.
   */
  public static ProbeOutcome.Success completeProbe(MediaProbe probe) {
    return ProbeOutcome.Success.builder()
        .container(
            ProbeContainer.builder()
                .format(probe.containerFormat())
                .duration(Optional.of(probe.duration()))
                .bitrate(OptionalLong.of(probe.bitrate()))
                .build())
        .streams(streams(probe))
        .keyframes(probe.keyframes())
        .chosenVideoStream(chosenVideoStream(probe))
        .build();
  }

  // The stored rule chooses the probe's frame rate from the declared rates, or refuses when the
  // probe has none, so the outcome reads back the probe's frame rate.
  private static Optional<ChosenVideoStream> chosenVideoStream(MediaProbe probe) {
    if (probe.videoPlaybackFacts().isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        new ChosenVideoStream(
            probe.videoPlaybackFacts().get().streamIndex(), frameRateRule(probe.framerate())));
  }

  private static FrameRateRule frameRateRule(OptionalDouble framerate) {
    if (framerate.isEmpty()) {
      return FrameRateRule.builder()
          .outputFrameRate(Optional.empty())
          .basis(OutputFrameRateBasis.REFUSED)
          .inputs(FrameRateRuleInputs.builder().build())
          .build();
    }

    return VideoPlaybackFactsFixture.declaredRateRule(declaredRate(framerate.getAsDouble()));
  }

  // The listed NTSC rates, such as 23.976, are whole rates times 1000/1001.
  private static Fraction declaredRate(double framerate) {
    var whole = (int) Math.round(framerate);
    if (Math.abs(framerate - whole) < 0.001) {
      return new Fraction(whole, 1);
    }

    return new Fraction((int) Math.round(framerate * 1.001) * 1000, 1001);
  }

  /**
   * Keyframes from zero through the media duration, one every {@code spacing}, in a millisecond
   * time base.
   */
  public static KeyframeKnowledge keyframesEvery(Duration spacing, Duration mediaDuration) {
    var keyframes =
        LongStream.iterate(
                0, time -> time <= mediaDuration.toMillis(), time -> time + spacing.toMillis())
            .mapToObj(time -> new KeyframePosition(time, time))
            .toList();
    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(keyframes)
            .build());
  }

  /** One value of each keyframe knowledge that does not hold the source's keyframe positions. */
  public static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return Stream.of(
        new KeyframeKnowledge.NotReported(),
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP));
  }

  private static List<StreamInfo> streams(MediaProbe probe) {
    if (!probe.streams().isEmpty()) {
      return probe.streams();
    }

    var facts = probe.videoPlaybackFacts();
    var videoIndex = facts.map(VideoPlaybackFacts::streamIndex).orElse(0);
    var streams = new ArrayList<StreamInfo>();
    streams.add(
        StreamInfo.builder()
            .index(videoIndex)
            .codecType("video")
            .codec(Optional.ofNullable(probe.videoCodec()))
            .width(OptionalInt.of(probe.width()))
            .height(OptionalInt.of(probe.height()))
            .framerate(probe.framerate())
            .dynamicRange(facts.map(VideoPlaybackFacts::dynamicRange))
            .bitDepth(facts.map(VideoPlaybackFacts::bitDepth).orElse(OptionalInt.empty()))
            .chromaSubsampling(facts.flatMap(VideoPlaybackFacts::chromaSubsampling))
            .codecString(facts.flatMap(VideoPlaybackFacts::codecString))
            .rotationDegrees(
                facts.map(VideoPlaybackFacts::rotationDegrees).orElse(OptionalInt.empty()))
            .containerCropping(facts.flatMap(VideoPlaybackFacts::containerCropping))
            .videoProperties(facts.flatMap(VideoPlaybackFacts::videoProperties))
            .build());
    if (probe.audioCodec() == null) {
      return streams;
    }

    streams.add(
        audioStreamBuilder(probe.audioCodec())
            .index(videoIndex + 1)
            .channels(probe.audioChannels())
            .bitrate(probe.audioBitrate())
            .build());
    return streams;
  }
}
