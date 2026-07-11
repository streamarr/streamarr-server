package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.repositories.media.DeletionIntentEntry;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Media Parent Deletion Retry Worker Tests")
class MediaParentDeletionRetryWorkerTest {

  @Test
  @DisplayName("Should retry every pending parent deletion")
  void shouldRetryEveryPendingParentDeletion() {
    var libraryId = UUID.randomUUID();
    var mediaFileId = UUID.randomUUID();
    var transactions = new StubDeletionTransactions();
    transactions.libraryIds = List.of(libraryId);
    transactions.mediaFileIds = List.of(mediaFileId);
    var deletionService = new RecordingDeletionService();

    new MediaParentDeletionRetryWorker(transactions, deletionService).retryPending();

    assertThat(deletionService.libraryAttempts).containsExactly(libraryId);
    assertThat(deletionService.mediaFileAttempts).containsExactly(mediaFileId);
  }

  @Test
  @DisplayName("Should isolate pending-load failures between parent types")
  void shouldIsolatePendingLoadFailuresBetweenParentTypes() {
    var mediaFileId = UUID.randomUUID();
    var transactions = new StubDeletionTransactions();
    transactions.libraryLoadFailure = new IllegalStateException("library load failed");
    transactions.mediaFileIds = List.of(mediaFileId);
    var deletionService = new RecordingDeletionService();

    assertThatNoException()
        .isThrownBy(
            () -> new MediaParentDeletionRetryWorker(transactions, deletionService).retryPending());

    assertThat(deletionService.libraryAttempts).isEmpty();
    assertThat(deletionService.mediaFileAttempts).containsExactly(mediaFileId);

    transactions.libraryLoadFailure = null;
    transactions.mediaLoadFailure = new IllegalStateException("media load failed");
    assertThatNoException()
        .isThrownBy(
            () -> new MediaParentDeletionRetryWorker(transactions, deletionService).retryPending());
  }

  @Test
  @DisplayName("Should continue a batch when one parent retry fails")
  void shouldContinueBatchWhenOneParentRetryFails() {
    var failedLibraryId = UUID.randomUUID();
    var nextLibraryId = UUID.randomUUID();
    var failedMediaFileId = UUID.randomUUID();
    var nextMediaFileId = UUID.randomUUID();
    var transactions = new StubDeletionTransactions();
    transactions.libraryIds = List.of(failedLibraryId, nextLibraryId);
    transactions.mediaFileIds = List.of(failedMediaFileId, nextMediaFileId);
    var deletionService = new RecordingDeletionService();
    deletionService.failedLibraryId = failedLibraryId;
    deletionService.failedMediaFileId = failedMediaFileId;

    assertThatNoException()
        .isThrownBy(
            () -> new MediaParentDeletionRetryWorker(transactions, deletionService).retryPending());

    assertThat(deletionService.libraryAttempts).containsExactly(failedLibraryId, nextLibraryId);
    assertThat(deletionService.mediaFileAttempts)
        .containsExactly(failedMediaFileId, nextMediaFileId);
  }

  @Test
  @DisplayName("Should advance beyond a full batch of persistent failures")
  void shouldAdvanceBeyondFullBatchOfPersistentFailures() {
    var libraryIds = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();
    var mediaFileIds = java.util.stream.Stream.generate(UUID::randomUUID).limit(51).toList();
    var transactions = new StubDeletionTransactions();
    transactions.libraryIds = libraryIds;
    transactions.mediaFileIds = mediaFileIds;
    var deletionService = new RecordingDeletionService();
    var worker = new MediaParentDeletionRetryWorker(transactions, deletionService);

    worker.retryPending();
    worker.retryPending();

    assertThat(deletionService.libraryAttempts).contains(libraryIds.getLast());
    assertThat(deletionService.mediaFileAttempts).contains(mediaFileIds.getLast());
  }

  private static final class StubDeletionTransactions extends MediaParentDeletionTransactions {

    private List<UUID> libraryIds = List.of();
    private List<UUID> mediaFileIds = List.of();
    private RuntimeException libraryLoadFailure;
    private RuntimeException mediaLoadFailure;

    private StubDeletionTransactions() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public List<DeletionIntentEntry> findPendingLibraryDeletions(int limit) {
      if (libraryLoadFailure != null) {
        throw libraryLoadFailure;
      }
      return entries(libraryIds, 0, limit);
    }

    @Override
    public List<DeletionIntentEntry> findPendingLibraryDeletionsAfter(
        DeletionIntentEntry cursor, int limit) {
      if (libraryLoadFailure != null) {
        throw libraryLoadFailure;
      }
      return entries(libraryIds, indexAfter(libraryIds, cursor), limit);
    }

    @Override
    public List<DeletionIntentEntry> findPendingStandaloneMediaFileDeletions(int limit) {
      if (mediaLoadFailure != null) {
        throw mediaLoadFailure;
      }
      return entries(mediaFileIds, 0, limit);
    }

    @Override
    public List<DeletionIntentEntry> findPendingStandaloneMediaFileDeletionsAfter(
        DeletionIntentEntry cursor, int limit) {
      if (mediaLoadFailure != null) {
        throw mediaLoadFailure;
      }
      return entries(mediaFileIds, indexAfter(mediaFileIds, cursor), limit);
    }

    private List<DeletionIntentEntry> entries(List<UUID> ids, int start, int limit) {
      var requestedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
      return java.util.stream.IntStream.range(start, Math.min(ids.size(), start + limit))
          .mapToObj(
              index -> new DeletionIntentEntry(ids.get(index), requestedAt.plusSeconds(index)))
          .toList();
    }

    private int indexAfter(List<UUID> ids, DeletionIntentEntry cursor) {
      return ids.indexOf(cursor.id()) + 1;
    }
  }

  private static final class RecordingDeletionService implements MediaParentDeletionService {

    private final List<UUID> libraryAttempts = new ArrayList<>();
    private final List<UUID> mediaFileAttempts = new ArrayList<>();
    private UUID failedLibraryId;
    private UUID failedMediaFileId;

    @Override
    public void deleteLibrary(UUID libraryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void resumeLibraryDeletion(UUID libraryId) {
      libraryAttempts.add(libraryId);
      if (libraryId.equals(failedLibraryId)) {
        throw new IllegalStateException("library retry failed");
      }
    }

    @Override
    public void deleteMediaFiles(UUID libraryId, Set<UUID> mediaFileIds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void resumeMediaFileDeletion(UUID mediaFileId) {
      mediaFileAttempts.add(mediaFileId);
      if (mediaFileId.equals(failedMediaFileId)) {
        throw new IllegalStateException("media retry failed");
      }
    }

    @Override
    public boolean isLibraryDeletionPending(UUID libraryId) {
      return false;
    }
  }
}
