package com.streamarr.server.services.streaming;

import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionEnforcementRepository;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransactionalStreamSessionLifecycle implements StreamSessionLifecycleTransactions {

  private final StreamSessionEnforcementRepository repository;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Instant> admit(StreamSessionAuthority authority, Duration provisioningTimeout) {
    return repository.admit(authority, provisioningTimeout);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean activate(StreamSessionAuthority authority, Duration provisioningTimeout) {
    return repository.activate(authority, provisioningTimeout);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
    return repository.touchIfPlaybackRequestMatches(authority);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<UUID> findTerminatingIds(int limit) {
    return repository.findTerminatingIds(limit);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
    return repository.findTerminatingIdsAfter(afterId, limit);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
    return repository.terminalizeByMediaFiles(termination);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
    return repository.terminalizeMissingMediaSources(terminalAt);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean terminalize(StreamSessionTermination termination) {
    return repository.terminalize(termination);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordTerminationIntent(StreamSessionTermination termination) {
    return repository.recordTerminationIntent(termination);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<StreamSessionTermination> findTerminationIntents() {
    return repository.findTerminationIntents();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean completeCreation(UUID streamSessionId) {
    return repository.completeCreation(streamSessionId);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean replayTerminationIntent(UUID streamSessionId) {
    return repository.replayTerminationIntent(streamSessionId);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean deleteTerminationIntent(UUID streamSessionId) {
    return repository.deleteTerminationIntent(streamSessionId);
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean deleteTerminating(UUID streamSessionId) {
    return repository.deleteTerminating(streamSessionId);
  }
}
