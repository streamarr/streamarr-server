package com.streamarr.server.services.library;

import com.streamarr.server.services.streaming.StreamSessionCleanup;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultMediaParentDeletionService implements MediaParentDeletionService {

  private final MediaParentDeletionTransactions transactions;
  private final StreamSessionCleanup streamCleanup;

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public void deleteLibrary(UUID libraryId) {
    finish(transactions.prepareLibraryDeletion(libraryId));
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public void resumeLibraryDeletion(UUID libraryId) {
    finish(transactions.resumeLibraryDeletion(libraryId));
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public void deleteMediaFiles(UUID libraryId, Set<UUID> mediaFileIds) {
    finish(transactions.prepareMediaFileDeletions(libraryId, mediaFileIds));
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public void resumeMediaFileDeletion(UUID mediaFileId) {
    finish(transactions.resumeMediaFileDeletion(mediaFileId));
  }

  @Override
  public boolean isLibraryDeletionPending(UUID libraryId) {
    return transactions.isLibraryDeletionPending(libraryId);
  }

  private void finish(LibraryDeletionPlan plan) {
    for (var streamSessionId : plan.streamSessionIds()) {
      try {
        streamCleanup.cleanup(streamSessionId);
      } catch (RuntimeException exception) {
        log.warn(
            "Library {} deletion will retry after stream {} cleanup failed",
            plan.target().libraryId(),
            streamSessionId,
            exception);
        return;
      }
    }
    transactions.finalizeLibraryDeletion(plan.target().libraryId());
  }

  private void finish(MediaFileDeletionPlan plan) {
    for (var streamSessionId : plan.streamSessionIds()) {
      try {
        streamCleanup.cleanup(streamSessionId);
      } catch (RuntimeException exception) {
        log.warn(
            "Media deletion will retry after stream {} cleanup failed", streamSessionId, exception);
        return;
      }
    }
    plan.targets().forEach(target -> transactions.finalizeMediaFileDeletion(target.mediaFileId()));
  }
}
