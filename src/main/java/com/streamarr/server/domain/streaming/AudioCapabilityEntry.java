package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * One audio capability entry (ADR 0049): an audio codec and the most channels that the player
 * accepts for it. {@code hls} tells whether the entry applies to HLS delivery, and {@code
 * directPlayContainers} lists the source containers from which the player opens such a stream by
 * direct play.
 */
@Builder
public record AudioCapabilityEntry(
    @NonNull AudioCodec codec,
    int maxChannels,
    boolean hls,
    @NonNull List<SourceContainer> directPlayContainers) {

  public AudioCapabilityEntry {
    directPlayContainers = List.copyOf(directPlayContainers);
  }

  /**
   * Whether this entry accepts direct play of the audio stream from the source container (ADR
   * 0049): the entry lists the container, names the stream's codec, and accepts its channel count.
   * A stream whose channel count is unknown is not accepted.
   */
  public boolean acceptsDirectPlay(@NonNull StreamInfo stream, @NonNull SourceContainer container) {
    if (!listsDirectPlayContainer(container) || !namesCodecOf(stream)) {
      return false;
    }

    var channels = stream.channels();
    return channels.isPresent() && channels.getAsInt() > 0 && channels.getAsInt() <= maxChannels;
  }

  /** Whether this entry lists the source container for direct play. */
  public boolean listsDirectPlayContainer(@NonNull SourceContainer container) {
    return directPlayContainers.contains(container);
  }

  // An AAC stream also needs a codec string that names an AAC profile that the declaration covers.
  private boolean namesCodecOf(StreamInfo stream) {
    if (stream.codec().flatMap(AudioCodec::ofProbedName).filter(codec::equals).isEmpty()) {
      return false;
    }

    return codec != AudioCodec.AAC
        || stream.codecString().filter(codec::coversCodecString).isPresent();
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioCapabilityEntryBuilder {
    private List<SourceContainer> directPlayContainers = List.of();
  }
}
