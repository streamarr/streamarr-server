package com.streamarr.server.domain.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.UnsupportedVideoEncodeException;
import java.nio.file.Path;
import java.util.OptionalDouble;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Transcode Request Tests")
class TranscodeRequestTest {

  @Test
  @DisplayName(
      "Should leave the frame rate and the pinned encoder empty when the builder omits them")
  void shouldLeaveFrameRateAndPinnedEncoderEmptyWhenBuilderOmitsThem() {
    var request = requestWithoutEncoderPreset().encoderPreset(EncoderPreset.FASTER).build();

    assertThat(request.framerate()).isEmpty();
    assertThat(request.pinnedEncoder()).isEmpty();
  }

  @Test
  @DisplayName("Should reject construction when the encoder preset is missing")
  void shouldRejectConstructionWhenTheEncoderPresetIsMissing() {
    var builder = requestWithoutEncoderPreset();

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("encoderPreset");
  }

  @Test
  @DisplayName("Should carry no keyframe seek position when the builder sets none")
  void shouldCarryNoKeyframeSeekPositionWhenTheBuilderSetsNone() {
    var request = requestWithoutEncoderPreset().encoderPreset(EncoderPreset.FAST).build();

    assertThat(request.keyframeSeekPosition()).isEmpty();
  }

  @Test
  @DisplayName("Should reject a null keyframe seek position when the builder receives one")
  void shouldRejectANullKeyframeSeekPositionWhenTheBuilderReceivesOne() {
    var builder = requestWithoutEncoderPreset().encoderPreset(EncoderPreset.FAST);

    assertThatThrownBy(() -> builder.keyframeSeekPosition(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("keyframeSeekPosition");
  }

  @Test
  @DisplayName("Should reject construction when the pinned encoder is missing")
  void shouldRejectConstructionWhenThePinnedEncoderIsMissing() {
    var request = requestWithoutEncoderPreset().encoderPreset(EncoderPreset.FASTER).build();

    assertThatThrownBy(() -> withoutPinnedEncoder(request))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("pinnedEncoder");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should plan the encode from the variant's output when the attempt encodes video")
  void shouldPlanTheEncodeFromTheVariantsOutputWhenTheAttemptEncodesVideo(TranscodeMode mode) {
    var request = attemptOf(mode).framerate(OptionalDouble.of(60)).build();

    assertThat(request.videoEncodeDecision())
        .contains(
            VideoEncodeDecision.of(
                EncodedVideoOutput.builder()
                    .codecFamily("h264")
                    .width(1920)
                    .height(1080)
                    .frameRate(60)
                    .bitrate(5_000_000L)
                    .build()));
    assertThat(request.videoEncodeDecision().map(VideoEncodeDecision::codecString))
        .contains("avc1.64002A");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should plan no encode when the attempt copies the video")
  void shouldPlanNoEncodeWhenTheAttemptCopiesTheVideo(TranscodeMode mode) {
    var request = attemptOf(mode).build();

    assertThat(request.videoEncodeDecision()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse to plan the encode when the attempt has no frame rate")
  void shouldRefuseToPlanTheEncodeWhenTheAttemptHasNoFrameRate() {
    var request = attemptOf(TranscodeMode.FULL_TRANSCODE).build();

    assertThatThrownBy(request::videoEncodeDecision)
        .isInstanceOf(UnsupportedVideoEncodeException.class)
        .hasMessageContaining("frame rate");
  }

  private static TranscodeRequest.TranscodeRequestBuilder attemptOf(TranscodeMode mode) {
    return requestWithoutEncoderPreset()
        .encoderPreset(EncoderPreset.FASTER)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.stereoAac())
                .subtitleDecision(SubtitleDecision.exclude())
                .build());
  }

  // The builder rejects a null pinned encoder before construction, so this calls the
  // canonical constructor.
  private static TranscodeRequest withoutPinnedEncoder(TranscodeRequest request) {
    return new TranscodeRequest(
        request.sessionId(),
        request.attemptId(),
        request.sourcePath(),
        request.seekPosition(),
        request.keyframeSeekPosition(),
        request.targetSegmentDuration(),
        request.framerate(),
        request.transcodeDecision(),
        request.width(),
        request.height(),
        request.bitrate(),
        request.variantLabel(),
        request.startSequenceNumber(),
        request.mediaSegmentCount(),
        request.encoderPreset(),
        null);
  }

  private static TranscodeRequest.TranscodeRequestBuilder requestWithoutEncoderPreset() {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movies/movie.mkv"))
        .variantLabel(StreamSession.defaultVariant())
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("h264")
                .audioDecision(AudioDecision.stereoAac())
                .subtitleDecision(SubtitleDecision.exclude())
                .build());
  }
}
