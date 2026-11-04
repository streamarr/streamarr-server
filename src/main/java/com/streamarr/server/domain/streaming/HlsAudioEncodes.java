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
  private static final List<ChannelLayout> SIDE_SURROUND_LAYOUTS =
      List.of(
          ChannelLayout.MONO,
          ChannelLayout.STEREO,
          ChannelLayout.FIVE_POINT_ZERO_SIDE,
          ChannelLayout.FIVE_POINT_ONE_SIDE);
  // Each encoder's own layout for each channel count that it writes (ADR 0050). FFmpeg's AC-3 and
  // E-AC-3 encoders stop at 5.1, and an AAC encode has at most six channels.
  private static final Map<String, List<ChannelLayout>> ENCODER_LAYOUTS =
      Map.of(
          "aac",
          List.of(
              ChannelLayout.MONO,
              ChannelLayout.STEREO,
              ChannelLayout.FIVE_POINT_ZERO,
              ChannelLayout.FIVE_POINT_ONE),
          "ac3",
          SIDE_SURROUND_LAYOUTS,
          "eac3",
          SIDE_SURROUND_LAYOUTS);
  private static final String SEVEN_POINT_ZERO = "7.0";
  private static final int MONO = 1;
  private static final int STEREO = 2;
  private static final int FIVE_POINT_ZERO = 5;
  private static final int FIVE_POINT_ONE = 6;
  // A 7.0 track has no low-frequency channel, so AAC, AC-3 and E-AC-3 encode it in 5.0: its four
  // surrounds fold into one pair (ADR 0050).
  private static final int SEVEN_POINT_ZERO_ENCODE_CHANNELS = FIVE_POINT_ZERO;

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
   * The encode for the track: the first codec of E-AC-3, AC-3 and AAC that keeps more than two
   * channels, or else AAC with at most two. The API boundary requires an AAC entry for HLS, so ADR
   * 0043's AAC encode always fits.
   */
  public static AudioDecision forTrack(
      @NonNull ClientCapabilityDeclaration declaration, @NonNull AudioTrackChannels track) {
    var channelMaximums = channelMaximums(declaration);
    return MULTICHANNEL_ENCODE_PREFERENCE.stream()
        .flatMap(codec -> multichannelEncode(channelMaximums, codec, track).stream())
        .findFirst()
        .orElseGet(() -> aacForTrack(declaration, track));
  }

  /**
   * The encode of the track in the codec, by its probed name, or empty when the server does not
   * encode the codec or the encode would keep at most two channels.
   */
  public static Optional<AudioDecision> multichannelEncode(
      @NonNull ClientCapabilityDeclaration declaration,
      @NonNull String codec,
      @NonNull AudioTrackChannels track) {
    if (!MULTICHANNEL_ENCODE_PREFERENCE.contains(codec)) {
      return Optional.empty();
    }

    return multichannelEncode(channelMaximums(declaration), codec, track);
  }

  /** The AAC encode of the track, with at most two channels. */
  public static AudioDecision aacForTrack(
      @NonNull ClientCapabilityDeclaration declaration, @NonNull AudioTrackChannels track) {
    int aacMaximum = channelMaximum(declaration, "aac").orElse(STEREO);
    return transcode("aac", Math.min(Math.min(channelsToEncode(track), STEREO), aacMaximum));
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
      Map<String, Integer> channelMaximums, String codec, AudioTrackChannels track) {
    int maximum = Math.min(channelMaximums.getOrDefault(codec, 0), mostChannels(codec));
    int channels = channelsWithin(maximum, channelsToEncode(track));
    if (channels <= STEREO) {
      return Optional.empty();
    }

    return Optional.of(transcode(codec, channels));
  }

  // The channels of the track that an encode keeps before a channel maximum caps them: 5 channels
  // and a 7.0 track in the encoder's 5.0 layout, and any other track of 6 or more in its 5.1
  // layout. The server's own encode of a 3- or 4-channel track writes stereo (ADR 0050).
  private static int channelsToEncode(AudioTrackChannels track) {
    if (track.layout().filter(SEVEN_POINT_ZERO::equals).isPresent()) {
      return SEVEN_POINT_ZERO_ENCODE_CHANNELS;
    }

    if (track.count() >= FIVE_POINT_ONE) {
      return FIVE_POINT_ONE;
    }

    return switch (track.count()) {
      case MONO -> MONO;
      case FIVE_POINT_ZERO -> FIVE_POINT_ZERO;
      default -> STEREO;
    };
  }

  // A track with more channels than a maximum of 3 or 4 has no layout of its own at that count, so
  // it encodes in stereo. Over a maximum of 5, it encodes in 5.0. Over a maximum of 7, it encodes
  // in 5.1 (ADR 0050).
  private static int channelsWithin(int maximum, int channels) {
    if (channels <= maximum) {
      return channels;
    }

    if (maximum >= FIVE_POINT_ONE) {
      return FIVE_POINT_ONE;
    }

    if (maximum == FIVE_POINT_ZERO) {
      return FIVE_POINT_ZERO;
    }

    return Math.min(maximum, STEREO);
  }

  private static int mostChannels(String codec) {
    return ENCODER_LAYOUTS.get(codec).getLast().getChannels();
  }

  private static AudioDecision transcode(String codec, int channels) {
    var layout =
        ENCODER_LAYOUTS.get(codec).stream()
            .filter(candidate -> candidate.getChannels() == channels)
            .findFirst()
            .orElseThrow();
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(channels)
        .channelLayout(Optional.of(layout))
        .bitrate(AudioDecision.bitrateFor(codec, channels))
        .build();
  }
}
