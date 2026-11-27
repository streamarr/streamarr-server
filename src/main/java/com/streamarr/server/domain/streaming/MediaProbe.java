package com.streamarr.server.domain.streaming;

import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.media.LearnedStreamEnd;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
 * it, and is empty when the probe stored none, which every version before 7 does. {@code
 * sourceSnapshot} names the source file that the stored outcome describes, and is empty for a probe
 * that playback did not read from a stored outcome.
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
    @NonNull Optional<StreamMeasurements> streamMeasurements,
    @NonNull Optional<SourceFileSnapshot> sourceSnapshot) {

  // ffprobe's names for PGS, VobSub and DVB subtitles.
  private static final Set<String> IMAGE_SUBTITLE_CODECS =
      Set.of("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle");

  // The smallest high-definition picture is 1280 wide, and the largest standard-definition
  // picture is 576 high.
  private static final int HIGH_DEFINITION_WIDTH = 1280;
  private static final int STANDARD_DEFINITION_HEIGHT = 576;

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
    private Optional<SourceFileSnapshot> sourceSnapshot = Optional.empty();
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
   * The assumed source matrix of the chosen video stream (ADR 0013): BT.709 when the probed picture
   * is at least 1280 wide or more than 576 high, else BT.601. Empty when the stream names a matrix,
   * when the probe stored no color facts, and when the stream's compatible base is PQ, HLG or none.
   */
  public Optional<AssumedSourceMatrix> assumedSourceMatrix() {
    return videoPlaybackFacts
        .filter(facts -> hasSdrOrUnspecifiedBase(facts.dynamicRange()))
        .flatMap(VideoPlaybackFacts::videoProperties)
        .filter(properties -> properties.matrixCoefficients().isEmpty())
        .map(_ -> matrixOfThePictureSize());
  }

  // An encode converts only such a source without tone mapping (ADR 0013).
  private static boolean hasSdrOrUnspecifiedBase(VideoDynamicRange range) {
    return switch (range.compatibleBase()) {
      case SDR, UNSPECIFIED -> true;
      case PQ, HLG, NONE -> false;
    };
  }

  private AssumedSourceMatrix matrixOfThePictureSize() {
    if (width >= HIGH_DEFINITION_WIDTH || height > STANDARD_DEFINITION_HEIGHT) {
      return AssumedSourceMatrix.BT709;
    }

    return AssumedSourceMatrix.BT601;
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
   * The file's audio streams in stream order. {@link #audioStreams} lists the session's audio track
   * first instead, once {@link #withSessionAudioTrackAt} has chosen one.
   */
  public List<StreamInfo> audioStreamsInStreamOrder() {
    return audioStreams().stream().sorted(Comparator.comparingInt(StreamInfo::index)).toList();
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
   * Whether every player that cannot select an audio track starts on the file's first audio track
   * in stream order (ADR 0045). Chromium and Firefox start on the first audio track, whatever a
   * Matroska track's default flag says. In an MP4 or MOV file, Chromium skips every audio track
   * whose enabled flag, which the probe reports as the default flag, is clear, unless no audio or
   * video track has the flag set; it plays no audio when only a video track has it. True when the
   * file has no audio track.
   */
  public boolean startsOnTheFirstAudioTrack() {
    var inStreamOrder = audioStreamsInStreamOrder();
    if (inStreamOrder.isEmpty() || !marksEnabledTracks()) {
      return true;
    }

    return inStreamOrder.getFirst().isDefault() || noAudioOrVideoTrackIsEnabled();
  }

  private boolean noAudioOrVideoTrackIsEnabled() {
    return streams.stream()
        .filter(stream -> "audio".equals(stream.codecType()) || "video".equals(stream.codecType()))
        .noneMatch(StreamInfo::isDefault);
  }

  // Chromium reads the enabled flag only in the files that FFmpeg's MP4 and MOV demuxer reads.
  private boolean marksEnabledTracks() {
    return sourceContainer()
        .filter(container -> container == SourceContainer.MP4 || container == SourceContainer.MOV)
        .isPresent();
  }

  /**
   * This probe with the audio track at {@code position}, counting from 0 among the file's audio
   * tracks in stream order, as the session's audio track: it takes the first audio stream's place,
   * and the probe's audio facts come from it. {@code position} must name one of the file's audio
   * tracks.
   */
  public MediaProbe withSessionAudioTrackAt(int position) {
    var inStreamOrder = audioStreamsInStreamOrder();
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

  /**
   * This probe with the audio stream that the session plays first among the streams, and the audio
   * facts taken from it, so every decision about the session's audio reads that stream (ADR 0043).
   * The stream is the first eligible stream in the first of {@code languages}, in order, that the
   * file holds: one in exactly that language before one that shares its base language. A
   * commentary, an audio description, a clean-effects stream and a dependent stream are never
   * eligible, whether a disposition or the stream's name marks them. When no language matches, the
   * file's own default plays: the first eligible stream whose default flag is set, else the first
   * eligible stream. When no stream is eligible, the same rule picks among the streams that are not
   * dependent, and a file of dependent streams alone keeps its first audio stream.
   */
  public MediaProbe withSessionAudio(@NonNull List<Language> languages) {
    var eligible = audioStreams().stream().filter(MediaProbe::isEligibleSessionAudio).toList();
    var complete = audioStreams().stream().filter(stream -> !isDependent(stream)).toList();
    return languages.stream()
        .flatMap(language -> matchingAudio(eligible, language).stream())
        .findFirst()
        .or(() -> fileDefault(eligible))
        .or(() -> fileDefault(complete))
        .map(this::withAudioFirst)
        .orElse(this);
  }

  private static Optional<StreamInfo> fileDefault(List<StreamInfo> candidates) {
    return candidates.stream()
        .filter(StreamInfo::isDefault)
        .findFirst()
        .or(() -> candidates.stream().findFirst());
  }

  private static boolean isDependent(StreamInfo stream) {
    return stream.audioProperties().filter(AudioStreamProperties::dependent).isPresent();
  }

  private static Optional<StreamInfo> matchingAudio(List<StreamInfo> eligible, Language language) {
    return eligible.stream()
        .filter(stream -> languageOf(stream).filter(language::equals).isPresent())
        .findFirst()
        .or(
            () ->
                eligible.stream()
                    .filter(
                        stream ->
                            languageOf(stream)
                                .filter(tagged -> tagged.base() == language.base())
                                .isPresent())
                    .findFirst());
  }

  private static Optional<Language> languageOf(StreamInfo stream) {
    return stream.language().flatMap(Language::ofFileCode);
  }

  private static boolean isEligibleSessionAudio(StreamInfo stream) {
    if (stream.comment().orElse(false)
        || AudioTrackMarks.isNamedCommentaryOrAudioDescription(stream)) {
      return false;
    }

    return stream
        .audioProperties()
        .filter(
            properties ->
                properties.visualImpaired()
                    || properties.descriptions()
                    || properties.cleanEffects()
                    || properties.dependent())
        .isEmpty();
  }

  public List<StreamInfo> subtitleStreams() {
    return streams.stream().filter(s -> "subtitle".equals(s.codecType())).toList();
  }

  /** The chosen video stream's probed end time, or empty when the probe states none. */
  public Optional<Duration> videoEndTime() {
    return chosenVideoStream().flatMap(StreamInfo::endTime);
  }

  /**
   * Whether the chosen video stream ends at least {@code margin} before another stream of the
   * source ends, by their probed end times (ADR 0037, early ends). The probe states end times for
   * audio and video streams only. A stream whose end time the probe does not state never ends
   * early, and never makes another stream end early.
   */
  public boolean videoEndsEarly(@NonNull Duration margin) {
    return endsEarly(chosenVideoStream(), margin);
  }

  /** Whether the session's audio stream ends early, by the rule of {@link #videoEndsEarly}. */
  public boolean sessionAudioEndsEarly(@NonNull Duration margin) {
    return endsEarly(audioStreams().stream().findFirst(), margin);
  }

  private Optional<StreamInfo> chosenVideoStream() {
    return videoPlaybackFacts.flatMap(
        facts -> streams.stream().filter(s -> s.index() == facts.streamIndex()).findFirst());
  }

  private boolean endsEarly(Optional<StreamInfo> stream, Duration margin) {
    if (stream.isEmpty() || stream.get().endTime().isEmpty()) {
      return false;
    }

    var earliestLaterEnd = stream.get().endTime().get().plus(margin);
    return streams.stream()
        .filter(other -> other.index() != stream.get().index())
        .flatMap(other -> other.endTime().stream())
        .anyMatch(end -> end.compareTo(earliestLaterEnd) >= 0);
  }

  /**
   * This probe with each learned end time in place of the end time that a stream's container header
   * does not state (ADR 0037, early ends). A stated end time stays.
   */
  public MediaProbe withLearnedEndTimes(@NonNull List<LearnedStreamEnd> learned) {
    var learnedByIndex =
        learned.stream()
            .collect(Collectors.toMap(LearnedStreamEnd::streamIndex, LearnedStreamEnd::endTime));
    var withLearned =
        streams.stream().map(stream -> withLearnedEndTime(stream, learnedByIndex)).toList();
    return toBuilder().streams(withLearned).build();
  }

  private static StreamInfo withLearnedEndTime(
      StreamInfo stream, Map<Integer, Duration> learnedByIndex) {
    var learned = learnedByIndex.get(stream.index());
    if (stream.endTime().isPresent() || learned == null) {
      return stream;
    }

    return stream.toBuilder().endTime(Optional.of(learned)).build();
  }

  /** Whether the probe lists the stream and its container header states no end time for it. */
  public boolean statesNoEndTimeFor(int streamIndex) {
    return streams.stream()
        .anyMatch(stream -> stream.index() == streamIndex && stream.endTime().isEmpty());
  }
}
