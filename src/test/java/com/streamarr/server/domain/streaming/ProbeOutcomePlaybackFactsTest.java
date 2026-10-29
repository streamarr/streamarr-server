package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe outcome playback facts")
class ProbeOutcomePlaybackFactsTest {

  private static final ContainerCropping LETTERBOX =
      ContainerCropping.builder().top(140).bottom(140).build();

  @Test
  @DisplayName("Should describe the chosen video stream when cover art comes first")
  void shouldDescribeTheChosenVideoStreamWhenCoverArtComesFirst() {
    var rule = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(25, 1));

    var probe = version4Outcome(rule).mediaProbe();

    assertThat(probe.width()).isEqualTo(1920);
    assertThat(probe.videoPlaybackFacts())
        .hasValueSatisfying(
            facts -> {
              assertThat(facts.streamIndex()).isEqualTo(1);
              assertThat(facts.codec()).contains(ProbedVideoCodec.H264);
              assertThat(facts.codecString()).contains("avc1.640028");
              assertThat(facts.rotationDegrees()).hasValue(-90);
              assertThat(facts.containerCropping()).contains(LETTERBOX);
              assertThat(facts.frameRateRule()).isEqualTo(rule);
            });
  }

  @Test
  @DisplayName("Should play at the rule's exact output frame rate when the rule chose one")
  void shouldPlayAtTheRulesExactOutputFrameRateWhenTheRuleChoseOne() {
    var rule = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24_000, 1_001));

    var probe = version4Outcome(rule).mediaProbe();

    assertThat(probe.framerate()).hasValue(24_000.0 / 1_001);
  }

  @Test
  @DisplayName("Should leave the playback frame rate unknown when the rule refused one")
  void shouldLeaveThePlaybackFrameRateUnknownWhenTheRuleRefusedOne() {
    var refused =
        FrameRateRule.builder()
            .outputFrameRate(Optional.empty())
            .basis(OutputFrameRateBasis.REFUSED)
            .inputs(FrameRateRuleInputs.builder().build())
            .build();

    var probe = version4Outcome(refused).mediaProbe();

    assertThat(probe.framerate()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the probed frame rate and report no facts when the outcome predates them")
  void shouldKeepTheProbedFrameRateAndReportNoFactsWhenTheOutcomePredatesThem() {
    var outcome =
        new ProbeOutcome.Success(
            ProbeContainer.builder().build(),
            List.of(recording().build()),
            new KeyframeKnowledge.NotReported());

    var probe = outcome.mediaProbe();

    assertThat(probe.framerate()).hasValue(29.97);
    assertThat(probe.videoPlaybackFacts()).isEmpty();
  }

  private static ProbeOutcome.Success version4Outcome(FrameRateRule rule) {
    var coverArt =
        StreamInfo.builder()
            .index(0)
            .codecType("video")
            .codec(Optional.of("mjpeg"))
            .width(OptionalInt.of(600))
            .height(OptionalInt.of(600))
            .build();
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().build())
        .streams(List.of(coverArt, recording().index(1).build()))
        .keyframes(new KeyframeKnowledge.NotReported())
        .chosenVideoStream(Optional.of(new ChosenVideoStream(1, rule)))
        .build();
  }

  // ffprobe's decimal rate for the recording differs from the rule's exact rate.
  private static StreamInfo.StreamInfoBuilder recording() {
    return StreamInfo.builder()
        .index(0)
        .codecType("video")
        .codec(Optional.of("h264"))
        .width(OptionalInt.of(1920))
        .height(OptionalInt.of(1080))
        .framerate(OptionalDouble.of(29.97))
        .codecString(Optional.of("avc1.640028"))
        .rotationDegrees(OptionalInt.of(-90))
        .containerCropping(Optional.of(LETTERBOX));
  }
}
