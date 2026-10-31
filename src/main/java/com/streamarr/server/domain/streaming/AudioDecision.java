package com.streamarr.server.domain.streaming;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record AudioDecision(
    AudioMode mode,
    String codec,
    int channels,
    long bitrate,
    @NonNull Optional<String> sourceCodecString) {

  // Every HLS variant is multiplexed fragmented MP4, which carries exactly these audio codecs.
  // Encodes use these codec strings; copies carry the source's probed codec string instead.
  // MP3 is transcoded rather than copied: the muxer refuses it below 16 kHz and writes a different
  // esds objectTypeIndication at or below 24 kHz than above.
  private static final Map<String, String> HLS_CODEC_STRINGS =
      Map.of(
          "aac", "mp4a.40.2",
          "ac3", "ac-3",
          "eac3", "ec-3",
          "flac", "fLaC",
          "opus", "Opus",
          "alac", "alac");

  public AudioDecision {
    if (mode == AudioMode.COPY && sourceCodecString.isEmpty()) {
      throw new IllegalArgumentException(
          "A decision that copies the audio needs the source's codec string");
    }
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioDecisionBuilder {
    private Optional<String> sourceCodecString = Optional.empty();
  }

  public static AudioDecision stereoAac() {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec("aac")
        .channels(2)
        .bitrate(128_000L)
        .build();
  }

  public static AudioDecision none() {
    return AudioDecision.builder().mode(AudioMode.NONE).build();
  }

  /** The audio codecs that HLS delivery carries, by their probed codec names. */
  public static Set<String> deliverableCodecs() {
    return HLS_CODEC_STRINGS.keySet();
  }

  public String hlsCodecString() {
    return switch (mode) {
      case NONE -> "";
      case COPY -> sourceCodecString.orElseThrow();
      case TRANSCODE -> encodedCodecString();
    };
  }

  private String encodedCodecString() {
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

  public static long bitrateFor(@NonNull String codec, int channels) {
    return switch (channels) {
      case 1 -> 64_000L;
      case 6 -> fivePointOneBitrate(codec);
      case 8 -> Math.max(512_000L, fivePointOneBitrate(codec));
      default -> 128_000L;
    };
  }

  // AC-3 takes 640 kb/s, the most that FFmpeg's AC-3 encoder produces however much more it is asked
  // for. E-AC-3 takes 768 kb/s, the highest 5.1 bitrate that streamed E-AC-3 commonly uses. Every
  // other codec keeps 384 kb/s. A 7.1 track never announces less than its codec's 5.1 bitrate.
  private static long fivePointOneBitrate(String codec) {
    return switch (codec) {
      case "ac3" -> 640_000L;
      case "eac3" -> 768_000L;
      default -> 384_000L;
    };
  }
}
