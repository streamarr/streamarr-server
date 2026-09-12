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
        .duration(
            container.hasDuration()
                ? Optional.of(
                    Duration.ofSeconds(
                        container.getDuration().getSeconds(), container.getDuration().getNanos()))
                : Optional.empty())
        .bitrate(
            container.hasBitrateBitsPerSecond()
                ? OptionalLong.of(container.getBitrateBitsPerSecond())
                : OptionalLong.empty())
        .build();
  }

  private StreamInfo stream(ProbeStreamInfo stream) {
    if (stream.getCodecType().isBlank()) {
      throw new ProbeExecutionException(
          new IllegalArgumentException("Worker probe stream is missing codec_type"));
    }

    return StreamInfo.builder()
        .index(stream.getIndex())
        .codecType(stream.getCodecType())
        .codec(stream.hasCodec() ? Optional.of(stream.getCodec()) : Optional.empty())
        .language(stream.hasLanguage() ? Optional.of(stream.getLanguage()) : Optional.empty())
        .channels(stream.hasChannels() ? OptionalInt.of(stream.getChannels()) : OptionalInt.empty())
        .bitrate(
            stream.hasBitrateBitsPerSecond()
                ? OptionalLong.of(stream.getBitrateBitsPerSecond())
                : OptionalLong.empty())
        .width(stream.hasWidth() ? OptionalInt.of(stream.getWidth()) : OptionalInt.empty())
        .height(stream.hasHeight() ? OptionalInt.of(stream.getHeight()) : OptionalInt.empty())
        .framerate(
            stream.hasFramerate()
                ? OptionalDouble.of(stream.getFramerate())
                : OptionalDouble.empty())
        .isDefault(stream.getIsDefault())
        .isForced(stream.getIsForced())
        .build();
  }
}
