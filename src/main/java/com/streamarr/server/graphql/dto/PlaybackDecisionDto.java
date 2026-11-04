package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.streaming.AudioFormat;
import com.streamarr.server.services.streaming.PlaybackDecision;
import com.streamarr.server.services.streaming.VideoFormat;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import lombok.Builder;

/** {@code audio} is null when the source has no audio. */
@Builder
public record PlaybackDecisionDto(
    int formatAttempt,
    String streamSessionId,
    TrackDeliveryDto<VideoFormatDto> video,
    TrackDeliveryDto<AudioFormatDto> audio,
    List<String> reasons) {

  public record TrackDeliveryDto<F>(String delivery, F source, F output) {}

  /** {@code frameRate} and {@code bitrate} are null when the probe found none. */
  @Builder
  public record VideoFormatDto(
      String codec,
      int width,
      int height,
      Double frameRate,
      Integer bitrate,
      String videoRange,
      boolean dolbyVision) {}

  /** {@code bitrate} is null when the probe found none. */
  public record AudioFormatDto(String codec, int channels, Integer bitrate) {}

  public static PlaybackDecisionDto from(PlaybackDecision decision) {
    var video = decision.video();
    var audio =
        decision
            .audio()
            .map(
                track ->
                    new TrackDeliveryDto<>(
                        track.delivery().name(), audio(track.source()), audio(track.output())));
    return PlaybackDecisionDto.builder()
        .formatAttempt(decision.formatAttempt())
        .streamSessionId(decision.streamSessionId().toString())
        .video(
            new TrackDeliveryDto<>(
                video.delivery().name(), video(video.source()), video(video.output())))
        .audio(audio.orElse(null))
        .reasons(decision.reasons().stream().map(Enum::name).toList())
        .build();
  }

  private static VideoFormatDto video(VideoFormat format) {
    return VideoFormatDto.builder()
        .codec(format.codec())
        .width(format.width())
        .height(format.height())
        .frameRate(nullable(format.frameRate()))
        .bitrate(graphqlInt(format.bitrate()))
        .videoRange(format.videoRange().name())
        .dolbyVision(format.dolbyVision())
        .build();
  }

  private static AudioFormatDto audio(AudioFormat format) {
    return new AudioFormatDto(format.codec(), format.channels(), graphqlInt(format.bitrate()));
  }

  private static Double nullable(OptionalDouble value) {
    if (value.isEmpty()) {
      return null;
    }

    return value.getAsDouble();
  }

  // GraphQL Int holds 32 bits, so a bitrate beyond it saturates instead of failing the field.
  private static Integer graphqlInt(OptionalLong value) {
    if (value.isEmpty()) {
      return null;
    }

    return (int) Math.min(value.getAsLong(), Integer.MAX_VALUE);
  }
}
