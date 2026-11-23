package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.EncodeFormat;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The caller reserves the session identifier before a worker can report a startup failure. The
 * stream session starts at {@code startPosition}, at the beginning of the media when it is empty.
 * {@code audioTrackPosition} names the audio track that the viewer chose, by its position, counting
 * from 0, among the file's audio tracks in stream order. When it is empty, the stream session plays
 * its own choice of track. {@code softwareContinuation} names the encode that the stream session
 * continues with the software encoder after a hardware encoder failure (ADR 0044). A codec family
 * in {@code excludedHardwareEncodeCodecFamilies} encodes with the software encoder, because its
 * hardware encoder failed earlier in the playback session.
 */
@Builder
public record CreateStreamSessionCommand(
    @NonNull UUID streamSessionId,
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull Set<String> excludedEncodeCodecFamilies,
    @NonNull Set<EncodeFormat> excludedEncodeFormats,
    @NonNull Set<String> excludedHardwareEncodeCodecFamilies,
    @NonNull Optional<Duration> startPosition,
    @NonNull OptionalInt audioTrackPosition,
    @NonNull Optional<SoftwareContinuation> softwareContinuation) {
  public CreateStreamSessionCommand {
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
    excludedEncodeFormats = Set.copyOf(excludedEncodeFormats);
    excludedHardwareEncodeCodecFamilies = Set.copyOf(excludedHardwareEncodeCodecFamilies);
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings("java:S9438")
  public static class CreateStreamSessionCommandBuilder {
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();
    private Set<EncodeFormat> excludedEncodeFormats = Set.of();
    private Set<String> excludedHardwareEncodeCodecFamilies = Set.of();
    private Optional<Duration> startPosition = Optional.empty();
    private OptionalInt audioTrackPosition = OptionalInt.empty();
    private Optional<SoftwareContinuation> softwareContinuation = Optional.empty();

    public CreateStreamSessionCommand build() {
      return new CreateStreamSessionCommand(
          streamSessionId,
          mediaFileId,
          identity,
          options,
          capabilityDeclaration,
          videoFormatConstraint,
          excludedEncodeCodecFamilies,
          excludedEncodeFormats,
          excludedHardwareEncodeCodecFamilies,
          startPosition,
          audioTrackPosition,
          softwareContinuation);
    }
  }
}
