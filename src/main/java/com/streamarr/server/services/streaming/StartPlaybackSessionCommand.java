package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Every format attempt of the playback session replays the options and capability declaration. A
 * format attempt starts at {@code startPosition} until playback moves from it.
 */
@Builder
public record StartPlaybackSessionCommand(
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration,
    @NonNull Optional<Duration> startPosition) {

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class StartPlaybackSessionCommandBuilder {
    private Optional<Duration> startPosition = Optional.empty();
  }
}
