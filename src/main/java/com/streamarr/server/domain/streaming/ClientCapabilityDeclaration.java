package com.streamarr.server.domain.streaming;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The client capability declaration that a stream session request carries (ADR 0049): the video and
 * audio capability entries of the player and display that the stream session uses. An output fits
 * only when one capability entry accepts every property of the output.
 */
@Builder
public record ClientCapabilityDeclaration(
    @NonNull List<VideoCapabilityEntry> video, @NonNull List<AudioCapabilityEntry> audio) {

  public ClientCapabilityDeclaration {
    video = List.copyOf(video);
    audio = List.copyOf(audio);
  }

  /** Whether one video capability entry accepts the stream copy over HLS. */
  public boolean acceptsHlsCopy(@NonNull VideoStreamCopy copy) {
    return hlsCopyRefusals(copy).isEmpty();
  }

  /** The first video capability entry that accepts the stream copy over HLS. */
  public Optional<VideoCapabilityEntry> hlsCopyEntry(@NonNull VideoStreamCopy copy) {
    return video.stream().filter(entry -> entry.acceptsHlsCopy(copy)).findFirst();
  }

  /**
   * Why no video capability entry accepts the stream copy over HLS: the codec family that no HLS
   * entry names, or the properties that the closest HLS entry of the codec family refuses. The
   * closest entry refuses the fewest properties, and the first declared wins a tie. Empty when an
   * entry accepts the copy.
   */
  public List<DecisionReason> hlsCopyRefusals(@NonNull VideoStreamCopy copy) {
    return video.stream()
        .filter(entry -> entry.appliesToHlsCopyOf(copy))
        .map(entry -> entry.hlsCopyRefusals(copy))
        .min(Comparator.comparingInt(List::size))
        .orElse(List.of(DecisionReason.VIDEO_CODEC_NOT_DECLARED));
  }

  /**
   * Whether the declaration covers direct play of the source from its source container (ADR 0049):
   * one video entry accepts the video stream and lists the container, and an audio entry that lists
   * the container accepts each audio track of the file. Every track counts, because a player may
   * start any track and the viewer can switch tracks inside the file.
   */
  public boolean acceptsDirectPlay(@NonNull MediaProbe source, @NonNull SourceContainer container) {
    var videoCovered =
        VideoStreamCopy.of(source)
            .filter(
                stream ->
                    video.stream().anyMatch(entry -> entry.acceptsDirectPlay(stream, container)))
            .isPresent();
    return videoCovered
        && source.audioStreams().stream()
            .allMatch(
                track ->
                    audio.stream().anyMatch(entry -> entry.acceptsDirectPlay(track, container)));
  }

  /**
   * The encoded output that one video capability entry accepts over HLS (ADR 0049), or empty when
   * no entry accepts an encode of the request. Among the entries' fitted outputs, the faster frame
   * rate wins, then the larger picture, then the higher bitrate.
   */
  public Optional<EncodedVideoOutput> fitHlsEncode(@NonNull EncodedVariantRequest request) {
    return video.stream()
        .map(entry -> entry.fitHlsEncode(request))
        .flatMap(Optional::stream)
        .max(EncodedVideoOutput.FASTER_FRAME_RATE_THEN_LARGER_PICTURE_THEN_HIGHER_BITRATE);
  }
}
