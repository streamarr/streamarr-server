package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.VideoRange;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Playback Decision Tests")
class PlaybackDecisionTest {

  private static final Map<String, UnaryOperator<PlaybackDecision.PlaybackDecisionBuilder>>
      REQUIRED_LISTS =
          Map.of(
              "reasons", builder -> builder.reasons(List.of()),
              "videoCapabilityEntryRefusals",
                  builder -> builder.videoCapabilityEntryRefusals(List.of()));

  @ParameterizedTest
  @MethodSource("requiredLists")
  @DisplayName("Should refuse a playback decision when it leaves out its reasons or entry refusals")
  void shouldRefuseAPlaybackDecisionWhenItLeavesOutItsReasonsOrEntryRefusals(String omitted) {
    var decision = directPlayDecisionBuilder();
    REQUIRED_LISTS.entrySet().stream()
        .filter(required -> !required.getKey().equals(omitted))
        .forEach(required -> required.getValue().apply(decision));

    assertThatThrownBy(decision::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining(omitted);
  }

  static Stream<String> requiredLists() {
    return REQUIRED_LISTS.keySet().stream().sorted();
  }

  private static PlaybackDecision.PlaybackDecisionBuilder directPlayDecisionBuilder() {
    var video =
        VideoFormat.builder()
            .codec("h264")
            .width(1920)
            .height(1080)
            .frameRate(OptionalDouble.empty())
            .bitrate(OptionalLong.empty())
            .videoRange(VideoRange.SDR)
            .build();
    return PlaybackDecision.builder()
        .formatAttempt(1)
        .streamSessionId(UUID.randomUUID())
        .video(new PlaybackDecision.TrackDelivery<>(PlaybackDelivery.DIRECT_PLAY, video, video))
        .audio(Optional.empty());
  }
}
