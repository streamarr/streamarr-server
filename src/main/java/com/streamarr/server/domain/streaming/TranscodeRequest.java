package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeRequest(
    @NonNull UUID sessionId,
    UUID attemptId,
    @NonNull Path sourcePath,
    int seekPosition,
    @NonNull Optional<Duration> keyframeSeekPosition,
    @NonNull Optional<Duration> readFromLastKeyframePresentingAtOrBefore,
    @NonNull Optional<ExactSegmentStarts> exactSegmentStarts,
    int targetSegmentDuration,
    @NonNull OptionalDouble framerate,
    @NonNull Optional<Fraction> outputFrameRate,
    @NonNull TranscodeDecision transcodeDecision,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber,
    int mediaSegmentCount,
    @NonNull EncoderPreset encoderPreset,
    @NonNull Optional<WorkerVerifiedEncoder> pinnedEncoder,
    @NonNull Optional<Duration> advertisedEnd) {

  /**
   * A stream copy attempt after segment 0 carries {@code keyframeSeekPosition}, the decode time of
   * the source keyframe that opens its first segment, and the worker seeks there instead of to
   * {@code seekPosition} seconds. An encoded attempt after segment 0 carries one when the source's
   * keyframes are known: the time, by which the container's demuxer seeks, of the last keyframe
   * that presents at least half a second before its first segment. Every encoded attempt after
   * segment 0 carries {@code readFromLastKeyframePresentingAtOrBefore}, that half a second before
   * its first segment on the attempt's own timeline: the worker keeps the keyframe seek position
   * when its read shows a picture by then, and otherwise finds the keyframe in the source (ADR
   * 0037, seek). Every other attempt carries neither. {@code outputFrameRate} is the exact rate at
   * which an encode runs, the source's output frame rate unless the variant names another; a stream
   * copy keeps the source's timing and carries none. {@code exactSegmentStarts} holds the stream
   * session's segment list (ADR 0037): every attempt of a stream copy session carries the copy's
   * exact starts, and every attempt on a plain grid that merges its final slot carries the grid's
   * whole-second starts; an attempt on any other plain grid carries none. {@code advertisedEnd} is
   * the end of the variant's last advertised media segment, up to which an encoded stream holds its
   * last frame or pads with silence (ADR 0037, early ends).
   */
  public TranscodeRequest {
    if (variantLabel == null) {
      variantLabel = StreamSession.defaultVariant();
    }

    if (outputFrameRate.isEmpty()) {
      outputFrameRate = sourceOutputFrameRate(transcodeDecision);
    }

    // The attempt id is minted only here. Dispatched jobs, the TranscodeHandle, and
    // stop/replace fencing all carry it verbatim.
    if (attemptId == null) {
      attemptId = UUID.randomUUID();
    }
  }

  /**
   * The codec profile, tier, level and codec string that the attempt forces when it encodes the
   * video; empty for a stream copy.
   */
  public Optional<VideoEncodeDecision> videoEncodeDecision() {
    if (transcodeDecision.transcodeMode().copiesVideo()) {
      return Optional.empty();
    }

    return Optional.of(
        VideoEncodeDecision.of(
            EncodedVideoOutput.builder()
                .codecFamily(transcodeDecision.videoCodecFamily())
                .width(width)
                .height(height)
                .probedFrameRate(outputFrameRate)
                .bitrate(bitrate)
                .format(transcodeDecision.videoEncodeFormat())
                .build()));
  }

  private static Optional<Fraction> sourceOutputFrameRate(TranscodeDecision decision) {
    if (decision.transcodeMode().copiesVideo()) {
      return Optional.empty();
    }

    return decision.outputFrameRate();
  }

  // Lombok skips its own builder() because this one exists.
  public static TranscodeRequestBuilder builder() {
    return new TranscodeRequestBuilder()
        .framerate(OptionalDouble.empty())
        .keyframeSeekPosition(Optional.empty())
        .readFromLastKeyframePresentingAtOrBefore(Optional.empty())
        .exactSegmentStarts(Optional.empty())
        .outputFrameRate(Optional.empty())
        .pinnedEncoder(Optional.empty())
        .advertisedEnd(Optional.empty());
  }
}
