package com.streamarr.server.services.playbackreport;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The figures that the player showed in its Playback Data panel when the viewer pressed Share. A
 * figure the player did not know yet is empty. {@code display} and {@code audioOutput} come from a
 * TV client, {@code screen} and {@code browser} from a web client. Bitrates are in bits per second.
 */
@Builder(toBuilder = true)
public record PlayerFigures(
    @NonNull Optional<QualityStep> qualityStep,
    @NonNull OptionalDouble observedBitrate,
    @NonNull OptionalDouble bufferSeconds,
    @NonNull OptionalInt droppedFrames,
    @NonNull OptionalInt stalls,
    @NonNull Optional<PictureSize> resolution,
    @NonNull Optional<String> videoCodec,
    @NonNull Optional<PlayerHdrMode> hdrMode,
    @NonNull Optional<PlayerAudio> audio,
    @NonNull Optional<PlayerDisplay> display,
    @NonNull Optional<PlayerAudioOutput> audioOutput,
    @NonNull Optional<PlayerScreen> screen,
    @NonNull Optional<PlayerBrowser> browser) {

  /** The HLS quality step that the player picked: step {@code number} of {@code count}. */
  @Builder
  public record QualityStep(int number, int count, int height, int bitrate) {}

  public record PictureSize(int width, int height) {}

  @Builder
  public record PlayerAudio(
      @NonNull String codec,
      @NonNull OptionalInt channels,
      @NonNull Optional<Boolean> dolbyAtmos) {}

  /**
   * The display mode that the TV switched to, and whether it matched the content's dynamic range
   * and frame rate. A figure is empty while the TV switches modes. {@code width} and {@code height}
   * are present together.
   */
  @Builder(toBuilder = true)
  public record PlayerDisplay(
      @NonNull OptionalInt width,
      @NonNull OptionalInt height,
      @NonNull Optional<PlayerHdrMode> hdrMode,
      @NonNull OptionalDouble refreshRate,
      @NonNull Optional<Boolean> dynamicRangeMatched,
      @NonNull Optional<Boolean> frameRateMatched) {}

  /** Where the TV sends its audio, such as HDMI, and how many channels that output accepts. */
  public record PlayerAudioOutput(@NonNull String route, int channels) {}

  /** The browser's screen: its size in CSS pixels, its pixel ratio and whether it reports HDR. */
  @Builder(toBuilder = true)
  public record PlayerScreen(int width, int height, double pixelRatio, boolean hdr) {}

  public record PlayerBrowser(@NonNull String name, @NonNull String version) {}
}
