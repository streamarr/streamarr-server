package com.streamarr.server.graphql.mutation.playbackreport;

import com.streamarr.server.services.playbackreport.PlayerFigures;
import com.streamarr.server.services.playbackreport.PlayerHdrMode;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import lombok.Builder;

/** {@code note} and each figure are null when the client left them out. */
@Builder
public record SharePlaybackReportInput(
    String playbackSessionId, String note, PlayerFiguresInput player) {

  @Builder
  public record PlayerFiguresInput(
      PlayerQualityStepInput qualityStep,
      Double observedBitrate,
      Double bufferSeconds,
      Integer droppedFrames,
      Integer stalls,
      PictureSizeInput resolution,
      String videoCodec,
      PlayerHdrMode hdrMode,
      PlayerAudioInput audio,
      PlayerDisplayInput display,
      PlayerAudioOutputInput audioOutput,
      PlayerScreenInput screen,
      PlayerBrowserInput browser) {

    public PlayerFigures toFigures() {
      return PlayerFigures.builder()
          .qualityStep(Optional.ofNullable(qualityStep).map(PlayerQualityStepInput::toStep))
          .observedBitrate(optionalDouble(observedBitrate))
          .bufferSeconds(optionalDouble(bufferSeconds))
          .droppedFrames(optionalInt(droppedFrames))
          .stalls(optionalInt(stalls))
          .resolution(
              Optional.ofNullable(resolution)
                  .map(size -> new PlayerFigures.PictureSize(size.width(), size.height())))
          .videoCodec(Optional.ofNullable(videoCodec))
          .hdrMode(Optional.ofNullable(hdrMode))
          .audio(Optional.ofNullable(audio).map(PlayerAudioInput::toAudio))
          .display(Optional.ofNullable(display).map(PlayerDisplayInput::toDisplay))
          .audioOutput(
              Optional.ofNullable(audioOutput)
                  .map(
                      output ->
                          new PlayerFigures.PlayerAudioOutput(output.route(), output.channels())))
          .screen(Optional.ofNullable(screen).map(PlayerScreenInput::toScreen))
          .browser(
              Optional.ofNullable(browser)
                  .map(it -> new PlayerFigures.PlayerBrowser(it.name(), it.version())))
          .build();
    }
  }

  public record PlayerQualityStepInput(int number, int count, int height, int bitrate) {

    PlayerFigures.QualityStep toStep() {
      return PlayerFigures.QualityStep.builder()
          .number(number)
          .count(count)
          .height(height)
          .bitrate(bitrate)
          .build();
    }
  }

  public record PictureSizeInput(int width, int height) {}

  public record PlayerAudioInput(String codec, Integer channels, boolean dolbyAtmos) {

    PlayerFigures.PlayerAudio toAudio() {
      return PlayerFigures.PlayerAudio.builder()
          .codec(codec)
          .channels(optionalInt(channels))
          .dolbyAtmos(dolbyAtmos)
          .build();
    }
  }

  @Builder
  public record PlayerDisplayInput(
      int width,
      int height,
      PlayerHdrMode hdrMode,
      double refreshRate,
      boolean dynamicRangeMatched,
      boolean frameRateMatched) {

    PlayerFigures.PlayerDisplay toDisplay() {
      return PlayerFigures.PlayerDisplay.builder()
          .width(width)
          .height(height)
          .hdrMode(hdrMode)
          .refreshRate(refreshRate)
          .dynamicRangeMatched(dynamicRangeMatched)
          .frameRateMatched(frameRateMatched)
          .build();
    }
  }

  public record PlayerAudioOutputInput(String route, int channels) {}

  public record PlayerScreenInput(int width, int height, double pixelRatio, boolean hdr) {

    PlayerFigures.PlayerScreen toScreen() {
      return PlayerFigures.PlayerScreen.builder()
          .width(width)
          .height(height)
          .pixelRatio(pixelRatio)
          .hdr(hdr)
          .build();
    }
  }

  public record PlayerBrowserInput(String name, String version) {}

  private static OptionalDouble optionalDouble(Double value) {
    if (value == null) {
      return OptionalDouble.empty();
    }

    return OptionalDouble.of(value);
  }

  private static OptionalInt optionalInt(Integer value) {
    if (value == null) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(value);
  }
}
