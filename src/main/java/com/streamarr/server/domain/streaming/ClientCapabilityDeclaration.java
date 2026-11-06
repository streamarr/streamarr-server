package com.streamarr.server.domain.streaming;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * The client capability declaration that a stream session request carries (ADR 0049): the video and
 * audio capability entries of the player and display that the stream session uses. An output fits
 * only when one capability entry accepts every property of the output. {@code
 * selectsAudioTrackInDirectPlay} states whether the player selects an audio track inside a file
 * that it direct-plays (ADR 0045).
 */
@Builder
public record ClientCapabilityDeclaration(
    @NonNull List<VideoCapabilityEntry> video,
    @NonNull List<AudioCapabilityEntry> audio,
    boolean selectsAudioTrackInDirectPlay) {

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
   * Whether one audio capability entry accepts the HLS copy of the audio stream with the channel
   * count that the copy announces.
   */
  public boolean acceptsHlsAudioCopy(@NonNull StreamInfo stream, int channels) {
    return audio.stream().anyMatch(entry -> entry.acceptsHlsCopy(stream, channels));
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
   * What each video capability entry for HLS of the copy's codec family refuses of the stream copy
   * (#396), each named by its position among the declaration's video capability entries. Empty when
   * no HLS entry names the codec family.
   */
  public List<VideoCapabilityEntryRefusal> hlsCopyRefusalsByEntry(@NonNull VideoStreamCopy copy) {
    return IntStream.range(0, video.size())
        .filter(position -> video.get(position).appliesToHlsCopyOf(copy))
        .mapToObj(
            position ->
                new VideoCapabilityEntryRefusal(
                    position, video.get(position).hlsCopyRefusals(copy)))
        .toList();
  }

  /**
   * Why the declaration does not cover direct play of the source from its source container (ADR
   * 0049), video before audio; empty when it covers the file. One video entry must accept the video
   * stream and list the container, and an audio entry that lists the container must accept each
   * audio track of the file. Every track counts, because a player may start any track and the
   * viewer can switch tracks inside the file. The video refusal names the furthest check that an
   * entry of the source's codec family passes: the container, then in MP4 or MOV the sample entry,
   * then the stream's properties. A file with audio whose container no audio entry lists names the
   * container, once.
   */
  public List<DecisionReason> directPlayRefusals(
      @NonNull MediaProbe source, @NonNull SourceContainer container) {
    return Stream.of(
            videoDirectPlayRefusal(source, container), audioDirectPlayRefusal(source, container))
        .flatMap(Optional::stream)
        .distinct()
        .toList();
  }

  private Optional<DecisionReason> videoDirectPlayRefusal(
      MediaProbe source, SourceContainer container) {
    var stream = VideoStreamCopy.of(source);
    if (stream.isEmpty()) {
      return Optional.of(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED);
    }

    var listingContainer =
        video.stream()
            .filter(entry -> entry.listsDirectPlayContainerOf(stream.get(), container))
            .toList();
    if (listingContainer.isEmpty()) {
      return Optional.of(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED);
    }

    var listingSampleEntry =
        listingContainer.stream()
            .filter(entry -> entry.listsDirectPlaySampleEntryOf(stream.get(), container))
            .toList();
    if (listingSampleEntry.isEmpty()) {
      return Optional.of(DecisionReason.DIRECT_PLAY_SAMPLE_ENTRY_NOT_DECLARED);
    }

    if (listingSampleEntry.stream()
        .noneMatch(entry -> entry.acceptsDirectPlay(stream.get(), container))) {
      return Optional.of(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED);
    }

    return Optional.empty();
  }

  private Optional<DecisionReason> audioDirectPlayRefusal(
      MediaProbe source, SourceContainer container) {
    var tracks = source.audioStreams();
    if (tracks.isEmpty()) {
      return Optional.empty();
    }

    var listingContainer =
        audio.stream().filter(entry -> entry.listsDirectPlayContainer(container)).toList();
    if (listingContainer.isEmpty()) {
      return Optional.of(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED);
    }

    var everyTrackAccepted =
        tracks.stream()
            .allMatch(
                track ->
                    listingContainer.stream()
                        .anyMatch(entry -> entry.acceptsDirectPlay(track, container)));
    if (!everyTrackAccepted) {
      return Optional.of(DecisionReason.DIRECT_PLAY_AUDIO_NOT_DECLARED);
    }

    return Optional.empty();
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
