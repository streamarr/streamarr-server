package com.streamarr.server.domain.streaming;

import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * The audio that a stream session can deliver in place of its audio decision under a maximum
 * bitrate, highest first (ADR 0050). The first step is the session's audio as the audio decision
 * chose it. Each later step is an encode with a lower bitrate than every step before it.
 */
public record AudioSteps(@NonNull List<AudioDecision> highestFirst) {

  private static final List<Long> EAC3_MULTICHANNEL_BITRATES =
      List.of(640_000L, 448_000L, 384_000L);
  private static final List<Long> AC3_MULTICHANNEL_BITRATES = List.of(448_000L, 384_000L);
  // Apple's HLS Authoring Specification rate for AAC 5.1, item 2.9, for each full-band channel.
  private static final long AAC_BITRATE_PER_FULL_BAND_CHANNEL = 64_000L;
  private static final List<Long> STEREO_BITRATES = List.of(128_000L, 96_000L, 64_000L);
  private static final long MONO_BITRATE = 64_000L;
  private static final int STEREO = 2;
  // 5.1 and 7.1 carry one low-frequency channel beside their full-band channels.
  private static final int LAYOUT_WITH_LOW_FREQUENCY_CHANNEL = 6;

  public AudioSteps {
    if (highestFirst.isEmpty()) {
      throw new IllegalArgumentException("Audio steps need the session's audio");
    }

    highestFirst = List.copyOf(highestFirst);
  }

  /**
   * The steps below the session's audio. Multichannel steps keep the codec and channels of the
   * encode that the server would choose for the track, which a capability entry already accepts,
   * and exist only when that encode has more than two channels. Then come AAC stereo steps, or one
   * AAC mono step for a mono track; every declaration carries an AAC entry for HLS (ADR 0049).
   */
  public static AudioSteps below(
      @NonNull AudioDecision sessionAudio, @NonNull ClientCapabilityDeclaration declaration) {
    if (sessionAudio.mode() == AudioMode.NONE) {
      return new AudioSteps(List.of(sessionAudio));
    }

    var normalizedChannels = AudioDecision.normalizeChannels(sessionAudio.channels());
    var candidates = new ArrayList<AudioDecision>();
    candidates.add(sessionAudio);
    candidates.addAll(multichannelSteps(HlsAudioEncodes.forTrack(declaration, normalizedChannels)));
    candidates.addAll(downmixSteps(HlsAudioEncodes.aacForTrack(declaration, normalizedChannels)));
    return new AudioSteps(eachLowerThanTheStepsBefore(candidates));
  }

  /** The stream session's audio as the audio decision chose it. */
  public AudioDecision sessionAudio() {
    return highestFirst.getFirst();
  }

  /** The step with the lowest bitrate. */
  public AudioDecision lowest() {
    return highestFirst.getLast();
  }

  private static List<AudioDecision> multichannelSteps(AudioDecision encode) {
    if (encode.channels() <= STEREO) {
      return List.of();
    }

    return multichannelBitrates(encode).stream()
        .map(bitrate -> encode.toBuilder().bitrate(bitrate).build())
        .toList();
  }

  private static List<Long> multichannelBitrates(AudioDecision encode) {
    return switch (encode.codec()) {
      case "eac3" -> EAC3_MULTICHANNEL_BITRATES;
      case "ac3" -> AC3_MULTICHANNEL_BITRATES;
      default -> List.of(AAC_BITRATE_PER_FULL_BAND_CHANNEL * fullBandChannels(encode.channels()));
    };
  }

  private static int fullBandChannels(int channels) {
    if (channels >= LAYOUT_WITH_LOW_FREQUENCY_CHANNEL) {
      return channels - 1;
    }

    return channels;
  }

  private static List<AudioDecision> downmixSteps(AudioDecision aac) {
    var bitrates = aac.channels() < STEREO ? List.of(MONO_BITRATE) : STEREO_BITRATES;
    return bitrates.stream().map(bitrate -> aac.toBuilder().bitrate(bitrate).build()).toList();
  }

  private static List<AudioDecision> eachLowerThanTheStepsBefore(List<AudioDecision> candidates) {
    var steps = new ArrayList<AudioDecision>();
    for (var candidate : candidates) {
      if (steps.isEmpty() || candidate.bitrate() < steps.getLast().bitrate()) {
        steps.add(candidate);
      }
    }

    return steps;
  }
}
