package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.repositories.streaming.SaveProgressCommand;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class FakeSessionProgressRepository extends FakeJpaRepository<SessionProgress>
    implements SessionProgressRepository {

  @Override
  public Optional<SessionProgress> findBySessionId(UUID sessionId) {
    return database.values().stream().filter(sp -> sessionId.equals(sp.getSessionId())).findFirst();
  }

  @Override
  public List<SessionProgress> findByUserIdAndMediaFileIdIn(
      UUID userId, Collection<UUID> mediaFileIds) {
    return database.values().stream()
        .filter(sp -> userId.equals(sp.getUserId()) && mediaFileIds.contains(sp.getMediaFileId()))
        .toList();
  }

  @Override
  public void deleteByUserIdAndMediaFileIds(UUID userId, Collection<UUID> mediaFileIds) {
    database
        .entrySet()
        .removeIf(
            entry ->
                userId.equals(entry.getValue().getUserId())
                    && mediaFileIds.contains(entry.getValue().getMediaFileId()));
  }

  @Override
  public Optional<SessionProgress> findMostRecentByUserIdAndMediaFileId(
      UUID userId, UUID mediaFileId) {
    return database.values().stream()
        .filter(sp -> userId.equals(sp.getUserId()) && mediaFileId.equals(sp.getMediaFileId()))
        .max(
            Comparator.comparing(
                sp -> sp.getLastModifiedOn() != null ? sp.getLastModifiedOn() : sp.getCreatedOn()));
  }

  @Override
  public void upsertProgress(SaveProgressCommand command) {
    var existing = findBySessionId(command.sessionId());
    if (existing.isPresent()) {
      var sp = existing.get();
      sp.setPositionSeconds(command.positionSeconds());
      sp.setPercentComplete(command.percentComplete());
      sp.setDurationSeconds(command.durationSeconds());
      return;
    }
    save(
        SessionProgress.builder()
            .sessionId(command.sessionId())
            .userId(command.userId())
            .mediaFileId(command.mediaFileId())
            .positionSeconds(command.positionSeconds())
            .percentComplete(command.percentComplete())
            .durationSeconds(command.durationSeconds())
            .build());
  }

  @Override
  public void deleteBySessionId(UUID sessionId) {
    database.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().getSessionId()));
  }
}
