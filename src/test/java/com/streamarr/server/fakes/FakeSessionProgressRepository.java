package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
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
  public List<SessionProgress> findByProfileIdAndMediaFileIdIn(
      UUID profileId, Collection<UUID> mediaFileIds) {
    return database.values().stream()
        .filter(
            sp -> profileId.equals(sp.getProfileId()) && mediaFileIds.contains(sp.getMediaFileId()))
        .toList();
  }

  @Override
  public void deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds) {
    database
        .entrySet()
        .removeIf(
            entry ->
                profileId.equals(entry.getValue().getProfileId())
                    && mediaFileIds.contains(entry.getValue().getMediaFileId()));
  }

  @Override
  public Optional<SessionProgress> findMostRecentByProfileIdAndMediaFileId(
      UUID profileId, UUID mediaFileId) {
    return database.values().stream()
        .filter(
            sp -> profileId.equals(sp.getProfileId()) && mediaFileId.equals(sp.getMediaFileId()))
        .max(
            Comparator.comparing(
                sp -> sp.getLastModifiedOn() != null ? sp.getLastModifiedOn() : sp.getCreatedOn()));
  }

  @Override
  public boolean upsertProgress(SaveWatchProgress progress) {
    var existing = findBySessionId(progress.sessionId());
    if (existing.isPresent()) {
      var sp = existing.get();
      sp.setPositionSeconds(progress.positionSeconds());
      sp.setPercentComplete(progress.percentComplete());
      sp.setDurationSeconds(progress.durationSeconds());
      return true;
    }
    save(
        SessionProgress.builder()
            .sessionId(progress.sessionId())
            .profileId(progress.profileId())
            .mediaFileId(progress.mediaFileId())
            .positionSeconds(progress.positionSeconds())
            .percentComplete(progress.percentComplete())
            .durationSeconds(progress.durationSeconds())
            .build());
    return true;
  }

  @Override
  public void deleteBySessionId(UUID sessionId) {
    database.entrySet().removeIf(entry -> sessionId.equals(entry.getValue().getSessionId()));
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    database.values().stream()
        .filter(sp -> fromProfileId.equals(sp.getProfileId()))
        .forEach(sp -> sp.setProfileId(toProfileId));
  }
}
