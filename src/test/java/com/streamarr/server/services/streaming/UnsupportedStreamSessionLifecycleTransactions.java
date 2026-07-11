package com.streamarr.server.services.streaming;

import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class UnsupportedStreamSessionLifecycleTransactions
    implements StreamSessionLifecycleTransactions {

  protected UnsupportedStreamSessionLifecycleTransactions() {}

  @Override
  public Optional<Instant> admit(StreamSessionAuthority authority, Duration provisioningTimeout) {
    throw unsupported();
  }

  @Override
  public boolean activate(StreamSessionAuthority authority, Duration provisioningTimeout) {
    throw unsupported();
  }

  @Override
  public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
    throw unsupported();
  }

  @Override
  public Optional<Instant> touchIfActiveAndOwnedBy(UUID streamSessionId, UUID profileId) {
    throw unsupported();
  }

  @Override
  public List<UUID> terminalizeExpiredActiveSessions(Duration retention, int limit) {
    throw unsupported();
  }

  @Override
  public Set<UUID> findAllSessionIds() {
    throw unsupported();
  }

  @Override
  public List<UUID> findTerminatingIds(int limit) {
    throw unsupported();
  }

  @Override
  public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
    throw unsupported();
  }

  @Override
  public List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
    throw unsupported();
  }

  @Override
  public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
    throw unsupported();
  }

  @Override
  public List<UUID> terminalizeRevokedAuthSessions(int limit) {
    throw unsupported();
  }

  @Override
  public boolean terminalize(StreamSessionTermination termination) {
    throw unsupported();
  }

  @Override
  public boolean recordTerminationIntent(StreamSessionTermination termination) {
    throw unsupported();
  }

  @Override
  public List<StreamSessionTermination> findTerminationIntents() {
    throw unsupported();
  }

  @Override
  public boolean completeCreation(UUID streamSessionId) {
    throw unsupported();
  }

  @Override
  public boolean replayTerminationIntent(UUID streamSessionId) {
    throw unsupported();
  }

  @Override
  public boolean deleteTerminationIntent(UUID streamSessionId) {
    throw unsupported();
  }

  @Override
  public boolean deleteTerminating(UUID streamSessionId) {
    throw unsupported();
  }

  private static UnsupportedOperationException unsupported() {
    return new UnsupportedOperationException("Test lifecycle operation is not configured");
  }
}
