package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * The playback view of a probe outcome. {@code videoPlaybackFacts} is empty when the outcome's
 * probe version reports no playback facts, which every version before 4 does. {@code
 * streamMeasurements} is empty when the server holds no usable measurement of the file's streams.
 * {@code majorBrand} is empty unless the file is an MP4 or MOV file whose probe version 7 outcome
 * names one.
 */
@Builder
public record MediaProbe(
    Duration duration,
    @NonNull OptionalDouble framerate,
    int width,
    int height,
    String videoCodec,
    String audioCodec,
    long bitrate,
    OptionalInt audioChannels,
    OptionalLong audioBitrate,
    Optional<String> containerFormat,
    @NonNull Optional<String> majorBrand,
    List<StreamInfo> streams,
    @NonNull KeyframeKnowledge keyframes,
    @NonNull Optional<VideoPlaybackFacts> videoPlaybackFacts,
    @NonNull Optional<StreamMeasurements> streamMeasurements) {

  // ffprobe's names for PGS, VobSub and DVB subtitles.
  private static final Set<String> IMAGE_SUBTITLE_CODECS =
      Set.of("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle");

  public MediaProbe {
    if (audioChannels == null) {
      audioChannels = OptionalInt.empty();
    }
    if (audioBitrate == null) {
      audioBitrate = OptionalLong.empty();
    }
    if (streams == null) {
      streams = List.of();
    }
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class MediaProbeBuilder {
    private Optional<String> containerFormat = Optional.empty();
    private Optional<String> majorBrand = Optional.empty();
    private OptionalDouble framerate = OptionalDouble.empty();
    private Optional<VideoPlaybackFacts> videoPlaybackFacts = Optional.empty();
    private Optional<StreamMeasurements> streamMeasurements = Optional.empty();
  }

  /**
   * The size that a player shows and that an encode scales from, after the container's cropping and
   * the stream's rotation. Without playback facts, the probed width and height.
   */
  public PictureSize displayedSize() {
    var coded = new PictureSize(width, height);
    return videoPlaybackFacts.map(facts -> facts.displayedSize(coded)).orElse(coded);
  }

  /**
   * Whether the chosen video stream is interlaced: its stored field order is TT, BB, TB or BT. A
   * progressive or unspecified order counts as progressive (streamarr/streamarr-server#653).
   */
  public boolean isInterlaced() {
    return videoPlaybackFacts.flatMap(VideoPlaybackFacts::interlacedFieldOrder).isPresent();
  }

  public List<StreamInfo> audioStreams() {
    return streams.stream().filter(s -> "audio".equals(s.codecType())).toList();
  }

  /**
   * The forced image subtitle track that applies while subtitles are off: a forced PGS, VobSub or
   * DVB track in the language of the selected audio, which is the first audio stream
   * (streamarr/streamarr-server#506). Empty when the selected audio names no language.
   */
  public Optional<StreamInfo> forcedImageSubtitleOfSelectedAudio() {
    var language = audioStreams().stream().findFirst().flatMap(StreamInfo::language);
    if (language.isEmpty()) {
      return Optional.empty();
    }

    return subtitleStreams().stream()
        .filter(StreamInfo::isForced)
        .filter(stream -> stream.codec().filter(IMAGE_SUBTITLE_CODECS::contains).isPresent())
        .filter(stream -> stream.language().filter(language.get()::equalsIgnoreCase).isPresent())
        .findFirst();
  }

  public List<StreamInfo> subtitleStreams() {
    return streams.stream().filter(s -> "subtitle".equals(s.codecType())).toList();
  }
}
