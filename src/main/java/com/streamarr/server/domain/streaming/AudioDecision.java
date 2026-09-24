package com.streamarr.server.domain.streaming;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;

@Builder
public record AudioDecision(AudioMode mode, String codec, int channels, long bitrate) {

  // Every HLS variant is multiplexed fragmented MP4, which carries exactly these audio codecs. Each
  // maps to the RFC 6381 form of what FFmpeg's mp4 muxer writes into the initialization segment.
  // MP3 is transcoded rather than copied: the muxer refuses it below 16 kHz and writes a different
  // esds objectTypeIndication at or below 24 kHz than above, and the probe reports no sample rate.
  private static final Map<String, String> HLS_CODEC_STRINGS =
      Map.of(
          "aac", "mp4a.40.2",
          "ac3", "ac-3",
          "eac3", "ec-3",
          "flac", "fLaC",
          "opus", "Opus",
          "alac", "alac");

  public static AudioDecision stereoAac() {
    return new AudioDecision(AudioMode.TRANSCODE, "aac", 2, 128_000L);
  }

  public static AudioDecision copy(String codec, int channels, long bitrate) {
    return new AudioDecision(AudioMode.COPY, codec, channels, bitrate);
  }

  public static AudioDecision none() {
    return new AudioDecision(AudioMode.NONE, null, 0, 0L);
  }

  /** The audio codecs that HLS delivery carries, by their probed codec names. */
  public static Set<String> deliverableCodecs() {
    return HLS_CODEC_STRINGS.keySet();
  }

  public String hlsCodecString() {
    if (codec == null) {
      return "";
    }

    return Optional.ofNullable(HLS_CODEC_STRINGS.get(codec))
        .orElseThrow(
            () -> new IllegalStateException("HLS delivery cannot carry audio codec " + codec));
  }

  public static int normalizeChannels(int sourceChannels) {
    return switch (sourceChannels) {
      case 1 -> 1;
      case 5, 6 -> 6;
      default -> sourceChannels >= 7 ? 8 : 2;
    };
  }

  public static long bitrateForChannels(int channels) {
    return switch (channels) {
      case 1 -> 64_000L;
      case 6 -> 384_000L;
      case 8 -> 512_000L;
      default -> 128_000L;
    };
  }
}
