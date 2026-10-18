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
    int targetSegmentDuration,
    @NonNull OptionalDouble framerate,
    @NonNull TranscodeDecision transcodeDecision,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber,
    int mediaSegmentCount,
    @NonNull EncoderPreset encoderPreset,
    @NonNull Optional<WorkerVerifiedEncoder> pinnedEncoder) {

  /**
   * A stream copy attempt after segment 0 carries {@code keyframeSeekPosition}, the decode time of
   * the source keyframe that opens its first segment, and the worker seeks there instead of to
   * {@code seekPosition} seconds. Every other attempt carries none.
   */
  public TranscodeRequest {
    if (variantLabel == null) {
      variantLabel = StreamSession.defaultVariant();
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
                .probedFrameRate(
                    transcodeDecision
                        .videoSource()
                        .map(VideoPlaybackFacts::frameRateRule)
                        .flatMap(FrameRateRule::outputFrameRate))
                .bitrate(bitrate)
                .build()));
  }

  // Lombok skips its own builder() because this one exists.
  public static TranscodeRequestBuilder builder() {
    return new TranscodeRequestBuilder()
        .framerate(OptionalDouble.empty())
        .keyframeSeekPosition(Optional.empty())
        .pinnedEncoder(Optional.empty());
  }
}
