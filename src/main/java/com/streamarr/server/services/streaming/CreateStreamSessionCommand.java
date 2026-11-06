package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.NetworkRoute;
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
 * its own choice of track. {@code softwareEncodeCodecFamily} names the codec family that the stream
 * session encodes with the software encoder after a hardware encoder failure (ADR 0044).
 */
@Builder
public record CreateStreamSessionCommand(
    @NonNull UUID streamSessionId,
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull NetworkRoute networkRoute,
    @NonNull VideoFormatConstraint videoFormatConstraint,
    @NonNull Set<String> excludedEncodeCodecFamilies,
    @NonNull Optional<Duration> startPosition,
    @NonNull OptionalInt audioTrackPosition,
    @NonNull Optional<String> softwareEncodeCodecFamily) {
  public CreateStreamSessionCommand {
    excludedEncodeCodecFamilies = Set.copyOf(excludedEncodeCodecFamilies);
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings("java:S9438")
  public static class CreateStreamSessionCommandBuilder {
    private VideoFormatConstraint videoFormatConstraint = VideoFormatConstraint.COPY_OR_ENCODE;
    private Set<String> excludedEncodeCodecFamilies = Set.of();
    private Optional<Duration> startPosition = Optional.empty();
    private OptionalInt audioTrackPosition = OptionalInt.empty();
    private Optional<String> softwareEncodeCodecFamily = Optional.empty();

    public CreateStreamSessionCommand build() {
      return new CreateStreamSessionCommand(
          streamSessionId,
          mediaFileId,
          identity,
          options,
          capabilityDeclaration,
          networkRoute,
          videoFormatConstraint,
          excludedEncodeCodecFamilies,
          startPosition,
          audioTrackPosition,
          softwareEncodeCodecFamily);
    }
  }
}
