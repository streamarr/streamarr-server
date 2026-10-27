package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.DurationOrBuilder;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.Chapter;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.domain.streaming.ChosenVideoStream;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.exceptions.ProbeCancelledException;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.exceptions.ProbeWorkersBusyException;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeChapter;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

final class RemoteProbeResultMapper {

  private static final long MAXIMUM_PROTOBUF_DURATION_SECONDS = 315_576_000_000L;
  private static final int MAXIMUM_PROTOBUF_DURATION_NANOS = 999_999_999;

  private final RemoteKeyframeKnowledgeMapper keyframeMapper = new RemoteKeyframeKnowledgeMapper();
  private final RemotePlaybackFactsMapper playbackFactsMapper =
      new RemotePlaybackFactsMapper(RemoteProbeResultMapper::toDuration);

  ProbeOutcome map(ProbeAttemptReport report) {
    var result = report.result();
    if (result.hasMedia()) {
      return success(result, report.keyframePositionsParts());
    }

    return switch (result.getFailure()) {
      case PROBE_FAILURE_INVALID_MEDIA -> new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA);
      case PROBE_FAILURE_NO_VIDEO_STREAM -> new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM);
      case PROBE_FAILURE_RESULT_TOO_LARGE -> new ProbeOutcome.Failure(ProbeError.RESULT_TOO_LARGE);
      // The worker lowered the probe's capacity after the server sent the probe. The probe stays
      // pending without a failure, and any worker with a free unit can take it.
      case PROBE_FAILURE_NO_FREE_CAPACITY ->
          throw new ProbeWorkersBusyException(
              "The worker had no free unit of the probe's capacity when the probe arrived");
      case PROBE_FAILURE_CANCELLED ->
          throw new ProbeCancelledException("Worker cancelled the probe: " + result.getFailure());
      case PROBE_FAILURE_SOURCE_UNAVAILABLE ->
          throw retryable(ItemFailureReason.SOURCE_INACCESSIBLE, result);
      case PROBE_FAILURE_UNSUPPORTED_VERSION ->
          throw retryable(ItemFailureReason.MISCONFIGURED, result);
      case PROBE_FAILURE_UNSPECIFIED ->
          throw contractViolation("Worker probe result reports no outcome");
      case PROBE_FAILURE_EXECUTION_FAILED, UNRECOGNIZED ->
          throw retryable(ItemFailureReason.TEMPORARY, result);
    };
  }

  private static ProbeExecutionException retryable(
      ItemFailureReason reason, ProbeAttemptResult result) {
    return new ProbeExecutionException(
        reason, "Worker probe reported a retryable failure: " + result.getFailure());
  }

  private static ProbeContractViolationException contractViolation(String message) {
    return new ProbeContractViolationException(message);
  }

  private ProbeOutcome.Success success(
      ProbeAttemptResult result, List<ProbeKeyframePositionsPart> keyframePositionsParts) {
    var media = result.getMedia();
    if (media.getStreamsList().stream()
        .noneMatch(stream -> "video".equals(stream.getCodecType()))) {
      throw contractViolation("Worker probe success contains no video stream");
    }

    var reportsPlaybackFacts = reportsPlaybackFacts(result.getProbeVersion());
    var reportsFrameFacts = reportsFrameFacts(result.getProbeVersion());
    var streams =
        media.getStreamsList().stream()
            .map(stream -> stream(stream, result.getProbeVersion()))
            .toList();
    var keyframes = keyframeMapper.map(media, keyframePositionsParts);
    var chosenVideoStream =
        reportsPlaybackFacts
            ? Optional.of(playbackFactsMapper.chosenVideoStream(media, streams))
            : Optional.<ChosenVideoStream>empty();
    if (chosenVideoStream.isPresent()
        && keyframes instanceof KeyframeKnowledge.Known(var positions)
        && positions.streamIndex() != chosenVideoStream.get().streamIndex()) {
      throw contractViolation(
          "Worker probe keyframes belong to another stream than the chosen one");
    }

    if (reportsFrameFacts && readsFramesOfAnotherStream(streams, chosenVideoStream.orElseThrow())) {
      throw contractViolation("Worker probe read the frames of a stream other than the chosen one");
    }

    if (reportsFrameFacts && leavesAv1StaticHdrValueUnflagged(streams, chosenVideoStream.get())) {
      throw contractViolation(
          "Worker probe left a missing static HDR value of an AV1 stream unflagged");
    }

    return ProbeOutcome.Success.builder()
        .container(container(media.getContainer(), result.getProbeVersion()))
        .streams(streams)
        .keyframes(keyframes)
        .chosenVideoStream(chosenVideoStream)
        .streamTitlesOmitted(titleOmission(media, reportsPlaybackFacts))
        .build();
  }

  private static Optional<Boolean> titleOmission(
      ProbeMediaInfo media, boolean reportsPlaybackFacts) {
    if (!reportsPlaybackFacts) {
      return Optional.empty();
    }

    if (media.getStreamTitlesOmitted()
        && media.getStreamsList().stream()
            .anyMatch(stream -> stream.hasTitle() || stream.hasHandlerName())) {
      throw contractViolation("Worker probe omitted its stream names and still listed some");
    }

    return Optional.of(media.getStreamTitlesOmitted());
  }

  private static boolean reportsPlaybackFacts(int probeVersion) {
    return Integer.compareUnsigned(probeVersion, ProbeVersion.FIRST_TO_REPORT_PLAYBACK_FACTS) >= 0;
  }

  private static boolean reportsFrameFacts(int probeVersion) {
    return Integer.compareUnsigned(probeVersion, ProbeVersion.FIRST_TO_REPORT_FRAME_FACTS) >= 0;
  }

  private static boolean readsFramesOfAnotherStream(
      List<StreamInfo> streams, ChosenVideoStream chosen) {
    return streams.stream()
        .filter(stream -> stream.index() != chosen.streamIndex())
        .flatMap(stream -> stream.videoProperties().stream())
        .anyMatch(VideoStreamProperties::firstFramesRead);
  }

  // The worker parses no AV1 OBU, so it cannot rule out a static HDR value that FFmpeg dropped
  // with a frame it failed to decode, and flags each value that the chosen AV1 stream lacks.
  private static boolean leavesAv1StaticHdrValueUnflagged(
      List<StreamInfo> streams, ChosenVideoStream chosen) {
    return streams.stream()
        .filter(stream -> stream.index() == chosen.streamIndex())
        .filter(stream -> stream.codec().filter("av1"::equals).isPresent())
        .anyMatch(RemoteProbeResultMapper::leavesStaticHdrValueUnflagged);
  }

  private static boolean leavesStaticHdrValueUnflagged(StreamInfo stream) {
    var properties = stream.videoProperties().orElseThrow();
    return (stream.masteringDisplay().isEmpty() && !properties.masteringDisplayOmitted())
        || (stream.contentLightLevel().isEmpty() && !properties.contentLightLevelOmitted());
  }

  private ProbeContainer container(ProbeContainerInfo container, int probeVersion) {
    return ProbeContainer.builder()
        .format(container.hasFormat() ? Optional.of(container.getFormat()) : Optional.empty())
        .duration(duration(container))
        .bitrate(
            container.hasBitrateBitsPerSecond()
                ? OptionalLong.of(container.getBitrateBitsPerSecond())
                : OptionalLong.empty())
        .chapters(chapters(container, probeVersion))
        .build();
  }

  private static ChapterKnowledge chapters(ProbeContainerInfo container, int probeVersion) {
    if (Integer.compareUnsigned(probeVersion, ProbeVersion.FIRST_TO_REPORT_CHAPTERS) < 0) {
      return new ChapterKnowledge.NotReported();
    }

    if (!container.getChaptersOmitted()) {
      return new ChapterKnowledge.Known(
          container.getChaptersList().stream().map(RemoteProbeResultMapper::chapter).toList());
    }

    if (container.getChaptersCount() > 0) {
      throw contractViolation("Worker probe omitted its chapters and still listed some");
    }

    return new ChapterKnowledge.Omitted();
  }

  private static Chapter chapter(ProbeChapter chapter) {
    if (chapter.hasTitle() && chapter.getTitleBytes().size() > Chapter.MAXIMUM_TITLE_BYTES) {
      throw contractViolation("Worker probe chapter title exceeds the title limit");
    }

    return Chapter.builder()
        .startTime(
            chapter.hasStartTime()
                ? Optional.of(toDuration(chapter.getStartTime()))
                : Optional.empty())
        .endTime(
            chapter.hasEndTime() ? Optional.of(toDuration(chapter.getEndTime())) : Optional.empty())
        .title(chapter.hasTitle() ? Optional.of(chapter.getTitle()) : Optional.empty())
        .build();
  }

  private Optional<Duration> duration(ProbeContainerInfo container) {
    if (!container.hasDuration()) {
      return Optional.empty();
    }

    var duration = toDuration(container.getDuration());
    if (duration.isNegative()) {
      return Optional.empty();
    }

    return Optional.of(duration);
  }

  private static Duration toDuration(DurationOrBuilder duration) {
    var seconds = duration.getSeconds();
    var nanos = duration.getNanos();
    var validSeconds =
        seconds >= -MAXIMUM_PROTOBUF_DURATION_SECONDS
            && seconds <= MAXIMUM_PROTOBUF_DURATION_SECONDS;
    var validNanos =
        nanos >= -MAXIMUM_PROTOBUF_DURATION_NANOS && nanos <= MAXIMUM_PROTOBUF_DURATION_NANOS;
    var consistentSign =
        seconds == 0 || nanos == 0 || Long.signum(seconds) == Integer.signum(nanos);
    if (!validSeconds || !validNanos || !consistentSign) {
      throw contractViolation("Worker probe duration violates the protobuf contract");
    }

    return Duration.ofSeconds(seconds, nanos);
  }

  private StreamInfo stream(ProbeStreamInfo stream, int probeVersion) {
    if (stream.getIndex() < 0) {
      throw contractViolation("Worker probe stream index exceeds the supported integer range");
    }

    if (stream.getCodecType().isBlank()) {
      throw contractViolation("Worker probe stream is missing codec_type");
    }

    var builder =
        StreamInfo.builder()
            .index(stream.getIndex())
            .codecType(stream.getCodecType())
            .codec(stream.hasCodec() ? Optional.of(stream.getCodec()) : Optional.empty())
            .language(stream.hasLanguage() ? Optional.of(stream.getLanguage()) : Optional.empty())
            .channels(optionalUnsignedInt(stream.hasChannels(), stream.getChannels()))
            .bitrate(
                stream.hasBitrateBitsPerSecond()
                    ? OptionalLong.of(stream.getBitrateBitsPerSecond())
                    : OptionalLong.empty())
            .width(optionalUnsignedInt(stream.hasWidth(), stream.getWidth()))
            .height(optionalUnsignedInt(stream.hasHeight(), stream.getHeight()))
            .framerate(
                Double.isFinite(stream.getFramerate()) && stream.getFramerate() > 0
                    ? OptionalDouble.of(stream.getFramerate())
                    : OptionalDouble.empty())
            .isDefault(stream.getIsDefault())
            .isForced(stream.getIsForced());
    var reportsFrameFacts = reportsFrameFacts(probeVersion);
    if (reportsPlaybackFacts(probeVersion)) {
      playbackFactsMapper.addStreamFacts(builder, stream, reportsFrameFacts);
    }

    if (reportsFrameFacts) {
      RemoteStreamPropertiesMapper.addStreamProperties(builder, stream);
    }

    return builder.build();
  }

  private OptionalInt optionalUnsignedInt(boolean present, int value) {
    return present && value >= 0 ? OptionalInt.of(value) : OptionalInt.empty();
  }
}
