package com.streamarr.server.domain.streaming;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import lombok.NonNull;

/**
 * The audio encodes that HLS delivery makes for a track, from the audio capability entries for HLS
 * of a client capability declaration (ADR 0043, ADR 0049).
 */
public final class HlsAudioEncodes {

  // A multichannel encode prefers the first of these codecs that keeps more than two channels.
  private static final List<String> MULTICHANNEL_ENCODE_PREFERENCE = List.of("eac3", "ac3", "aac");
  // FFmpeg's AC-3 and E-AC-3 encoders stop at 5.1.
  private static final int AC3_AND_EAC3_ENCODER_MAX_CHANNELS = 6;
  private static final int STEREO = 2;

  private HlsAudioEncodes() {}

  /**
   * The most channels that the declaration's audio capability entries for HLS accept for the codec,
   * by its probed name, or empty when no such entry names it. Several entries for one codec each
   * accept up to their own maximum, so the codec accepts up to the largest of them.
   */
  public static OptionalInt channelMaximum(
      @NonNull ClientCapabilityDeclaration declaration, @NonNull String codec) {
    var maximum = channelMaximums(declaration).get(codec);
    if (maximum == null) {
      return OptionalInt.empty();
    }

    return OptionalInt.of(maximum);
  }

  /**
   * The encode for a track with this normalized channel count: the first codec of E-AC-3, AC-3 and
   * AAC that keeps more than two channels, or else AAC with at most two. The API boundary requires
   * an AAC entry for HLS, so ADR 0043's AAC encode always fits.
   */
  public static AudioDecision forTrack(
      @NonNull ClientCapabilityDeclaration declaration, int normalizedChannels) {
    var channelMaximums = channelMaximums(declaration);
    return MULTICHANNEL_ENCODE_PREFERENCE.stream()
        .flatMap(codec -> multichannelEncode(channelMaximums, codec, normalizedChannels).stream())
        .findFirst()
        .orElseGet(() -> aacForTrack(declaration, normalizedChannels));
  }

  /**
   * The encode in the codec, by its probed name, for a track with this normalized channel count, or
   * empty when the server does not encode the codec or the encode would keep at most two channels.
   */
  public static Optional<AudioDecision> multichannelEncode(
      @NonNull ClientCapabilityDeclaration declaration,
      @NonNull String codec,
      int normalizedChannels) {
    if (!MULTICHANNEL_ENCODE_PREFERENCE.contains(codec)) {
      return Optional.empty();
    }

    return multichannelEncode(channelMaximums(declaration), codec, normalizedChannels);
  }

  /** The AAC encode for a track with this normalized channel count, with at most two channels. */
  public static AudioDecision aacForTrack(
      @NonNull ClientCapabilityDeclaration declaration, int normalizedChannels) {
    int aacMaximum = channelMaximum(declaration, "aac").orElse(STEREO);
    return transcode("aac", Math.min(Math.min(normalizedChannels, STEREO), aacMaximum));
  }

  private static Map<String, Integer> channelMaximums(ClientCapabilityDeclaration declaration) {
    return declaration.audio().stream()
        .filter(AudioCapabilityEntry::hls)
        .filter(entry -> AudioDecision.deliverableCodecs().contains(entry.codec().probedName()))
        .collect(
            Collectors.toMap(
                entry -> entry.codec().probedName(), AudioCapabilityEntry::maxChannels, Math::max));
  }

  private static Optional<AudioDecision> multichannelEncode(
      Map<String, Integer> channelMaximums, String codec, int normalizedChannels) {
    int channels =
        Math.min(
            normalizedChannels,
            Math.min(channelMaximums.getOrDefault(codec, 0), encoderChannelLimit(codec)));
    if (channels <= STEREO) {
      return Optional.empty();
    }

    return Optional.of(transcode(codec, channels));
  }

  private static int encoderChannelLimit(String codec) {
    return switch (codec) {
      case "ac3", "eac3" -> AC3_AND_EAC3_ENCODER_MAX_CHANNELS;
      default -> Integer.MAX_VALUE;
    };
  }

  private static AudioDecision transcode(String codec, int channels) {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(channels)
        .bitrate(AudioDecision.bitrateFor(codec, channels))
        .build();
  }
}
