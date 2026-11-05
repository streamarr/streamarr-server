package com.streamarr.server.domain.streaming;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.NonNull;

/**
 * The playback view of a probe outcome. {@code videoPlaybackFacts} is empty when the outcome's
 * probe version reports no playback facts, which every version before 4 does. {@code
 * streamMeasurements} is empty when the server holds no usable measurement of the file's streams.
 * {@code majorBrand} is empty unless the file is an MP4 or MOV file whose probe version 7 outcome
 * names one. {@code audioChannelLayout} is the first audio stream's channel layout as ffprobe names
 * it, and is empty when the probe stored none, which every version before 7 does.
 */
@Builder(toBuilder = true)
public record MediaProbe(
    Duration duration,
    @NonNull OptionalDouble framerate,
    int width,
    int height,
    String videoCodec,
    String audioCodec,
    long bitrate,
    OptionalInt audioChannels,
    @NonNull Optional<String> audioChannelLayout,
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
    private Optional<String> audioChannelLayout = Optional.empty();
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

  /** The file's source container, or empty when the probe's facts name no single one. */
  public Optional<SourceContainer> sourceContainer() {
    return containerFormat.flatMap(format -> SourceContainer.ofProbedFormat(format, majorBrand));
  }

  public List<StreamInfo> audioStreams() {
    return streams.stream().filter(s -> "audio".equals(s.codecType())).toList();
  }

  /**
   * The position, counting from 0, of the stream session's audio track among the file's audio
   * tracks in stream order, or empty when the file has no audio track. The stream session plays the
   * first audio stream that the probe lists.
   */
  public OptionalInt sessionAudioTrackPosition() {
    var tracks = audioStreams();
    if (tracks.isEmpty()) {
      return OptionalInt.empty();
    }

    var sessionTrack = tracks.getFirst().index();
    return OptionalInt.of(
        (int) tracks.stream().filter(track -> track.index() < sessionTrack).count());
  }

  /**
   * Whether a player that cannot select an audio track starts on the file's first audio track in
   * stream order. Such a player starts on the first default audio track, as an MP4 track's enabled
   * flag or a Matroska track's default flag marks it, or on the first audio track when none is
   * marked. True when the file has no audio track.
   */
  public boolean startsOnTheFirstAudioTrack() {
    var inStreamOrder =
        audioStreams().stream().sorted(Comparator.comparingInt(StreamInfo::index)).toList();
    return inStreamOrder.stream()
        .filter(StreamInfo::isDefault)
        .findFirst()
        .map(track -> track == inStreamOrder.getFirst())
        .orElse(true);
  }

  /**
   * This probe with the audio track at {@code position}, counting from 0 among the file's audio
   * tracks in stream order, as the session's audio track: it takes the first audio stream's place,
   * and the probe's audio facts come from it. {@code position} must name one of the file's audio
   * tracks.
   */
  public MediaProbe withSessionAudioTrackAt(int position) {
    var inStreamOrder =
        audioStreams().stream().sorted(Comparator.comparingInt(StreamInfo::index)).toList();
    return withAudioFirst(inStreamOrder.get(position));
  }

  // The chosen stream takes the first audio stream's place, so every other stream keeps its order.
  private MediaProbe withAudioFirst(StreamInfo chosen) {
    var firstAudio = audioStreams().getFirst();
    if (chosen == firstAudio) {
      return this;
    }

    var reordered = new ArrayList<StreamInfo>(streams.size());
    for (var stream : streams) {
      if (stream == firstAudio) {
        reordered.add(chosen);
      }

      if (stream != chosen) {
        reordered.add(stream);
      }
    }

    return toBuilder()
        .streams(List.copyOf(reordered))
        .audioCodec(chosen.codec().orElse(null))
        .audioChannels(chosen.channels())
        .audioChannelLayout(chosen.audioProperties().flatMap(AudioStreamProperties::channelLayout))
        .audioBitrate(chosen.bitrate())
        .build();
  }

  /**
   * Whether a forced PGS, VobSub or DVB subtitle track is in the language of any audio track. The
   * player shows such a track while subtitles are off (streamarr/streamarr-server#506), whichever
   * audio track plays.
   */
  public boolean hasForcedImageSubtitleInAnAudioLanguage() {
    var languages =
        audioStreams().stream()
            .flatMap(stream -> stream.language().stream())
            .map(LanguageCodes::canonical)
            .collect(Collectors.toSet());
    return subtitleStreams().stream()
        .filter(StreamInfo::isForced)
        .filter(stream -> stream.codec().filter(IMAGE_SUBTITLE_CODECS::contains).isPresent())
        .flatMap(stream -> stream.language().stream())
        .anyMatch(language -> languages.contains(LanguageCodes.canonical(language)));
  }

  public List<StreamInfo> subtitleStreams() {
    return streams.stream().filter(s -> "subtitle".equals(s.codecType())).toList();
  }
}
