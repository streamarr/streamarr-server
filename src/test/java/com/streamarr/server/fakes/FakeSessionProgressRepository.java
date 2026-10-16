package com.streamarr.server.fakes;

import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.repositories.streaming.SaveWatchProgress;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public class FakeSessionProgressRepository extends FakeJpaRepository<SessionProgress>
    implements SessionProgressRepository {

  private final Predicate<UUID> hasStreamSessionCompletion;

  /** A repository that sees no completions, so every progress report is saved. */
  public FakeSessionProgressRepository() {
    this.hasStreamSessionCompletion = _ -> false;
  }

  /** A repository that saves no progress for a stream session once it saved its completion. */
  public FakeSessionProgressRepository(FakeWatchHistoryRepository watchHistory) {
    this.hasStreamSessionCompletion = watchHistory::hasStreamSessionCompletion;
  }

  @Override
  public List<SessionProgress> findByProfileIdOrderByLastModifiedOnDesc(UUID profileId) {
    return database.values().stream()
        .filter(progress -> profileId.equals(progress.getProfileId()))
        .sorted(Comparator.comparing(SessionProgress::getLastModifiedOn).reversed())
        .toList();
  }

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
  public int deleteByProfileIdAndMediaFileIds(UUID profileId, Collection<UUID> mediaFileIds) {
    return removeWhere(
        progress ->
            profileId.equals(progress.getProfileId())
                && mediaFileIds.contains(progress.getMediaFileId()));
  }

  @Override
  public boolean upsertProgress(SaveWatchProgress progress) {
    if (hasStreamSessionCompletion.test(progress.sessionId())) {
      return false;
    }

    var existing = findBySessionId(progress.sessionId());
    if (existing.isPresent()) {
      return updateWhenChanged(existing.get(), progress);
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

  private boolean updateWhenChanged(SessionProgress sp, SaveWatchProgress progress) {
    if (sp.getPositionSeconds() == progress.positionSeconds()
        && Double.compare(sp.getPercentComplete(), progress.percentComplete()) == 0
        && sp.getDurationSeconds() == progress.durationSeconds()) {
      return false;
    }

    sp.setPositionSeconds(progress.positionSeconds());
    sp.setPercentComplete(progress.percentComplete());
    sp.setDurationSeconds(progress.durationSeconds());
    save(sp);
    return true;
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    return removeWhere(progress -> sessionId.equals(progress.getSessionId()));
  }

  private int removeWhere(Predicate<SessionProgress> matches) {
    var sizeBefore = database.size();
    database.entrySet().removeIf(entry -> matches.test(entry.getValue()));
    return sizeBefore - database.size();
  }

  @Override
  public void reassignProfile(UUID fromProfileId, UUID toProfileId) {
    database.values().stream()
        .filter(sp -> fromProfileId.equals(sp.getProfileId()))
        .forEach(sp -> sp.setProfileId(toProfileId));
  }
}
