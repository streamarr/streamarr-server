package com.streamarr.server.services.streaming.remote;

import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeError;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeContainerInfo;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

final class RemoteProbeResultMapper {

  private static final long MAXIMUM_PROTOBUF_DURATION_SECONDS = 315_576_000_000L;
  private static final int MAXIMUM_PROTOBUF_DURATION_NANOS = 999_999_999;

  ProbeOutcome map(ProbeAttemptResult result) {
    if (result.hasMedia()) {
      return success(result.getMedia());
    }

    return switch (result.getFailure()) {
      case PROBE_FAILURE_INVALID_MEDIA -> new ProbeOutcome.Failure(ProbeError.INVALID_MEDIA);
      case PROBE_FAILURE_NO_VIDEO_STREAM -> new ProbeOutcome.Failure(ProbeError.NO_VIDEO_STREAM);
      default ->
          throw new ProbeExecutionException(
              new IllegalStateException(
                  "Worker probe reported a retryable failure: " + result.getFailure()));
    };
  }

  private ProbeOutcome.Success success(ProbeMediaInfo media) {
    if (media.getStreamsList().stream()
        .noneMatch(stream -> "video".equals(stream.getCodecType()))) {
      throw new ProbeExecutionException(
          new IllegalArgumentException("Worker probe success contains no video stream"));
    }

    return new ProbeOutcome.Success(
        container(media.getContainer()),
        media.getStreamsList().stream().map(this::stream).toList());
  }

  private ProbeContainer container(ProbeContainerInfo container) {
    return ProbeContainer.builder()
        .format(container.hasFormat() ? Optional.of(container.getFormat()) : Optional.empty())
        .duration(duration(container))
        .bitrate(
            container.hasBitrateBitsPerSecond()
                ? OptionalLong.of(container.getBitrateBitsPerSecond())
                : OptionalLong.empty())
        .build();
  }

  private Optional<Duration> duration(ProbeContainerInfo container) {
    if (!container.hasDuration()) {
      return Optional.empty();
    }

    var seconds = container.getDuration().getSeconds();
    var nanos = container.getDuration().getNanos();
    var validSeconds =
        seconds >= -MAXIMUM_PROTOBUF_DURATION_SECONDS
            && seconds <= MAXIMUM_PROTOBUF_DURATION_SECONDS;
    var validNanos =
        nanos >= -MAXIMUM_PROTOBUF_DURATION_NANOS && nanos <= MAXIMUM_PROTOBUF_DURATION_NANOS;
    var consistentSign =
        seconds == 0 || nanos == 0 || Long.signum(seconds) == Integer.signum(nanos);
    if (!validSeconds || !validNanos || !consistentSign) {
      throw new ProbeExecutionException(
          new IllegalArgumentException("Worker probe duration violates the protobuf contract"));
    }

    return seconds < 0 || nanos < 0
        ? Optional.empty()
        : Optional.of(Duration.ofSeconds(seconds, nanos));
  }

  private StreamInfo stream(ProbeStreamInfo stream) {
    if (stream.getIndex() < 0) {
      throw new ProbeExecutionException(
          new IllegalArgumentException(
              "Worker probe stream index exceeds the supported integer range"));
    }

    if (stream.getCodecType().isBlank()) {
      throw new ProbeExecutionException(
          new IllegalArgumentException("Worker probe stream is missing codec_type"));
    }

    return StreamInfo.builder()
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
        .isForced(stream.getIsForced())
        .build();
  }

  private OptionalInt optionalUnsignedInt(boolean present, int value) {
    return present && value >= 0 ? OptionalInt.of(value) : OptionalInt.empty();
  }
}
