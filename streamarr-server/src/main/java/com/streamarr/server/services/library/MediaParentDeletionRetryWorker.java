package com.streamarr.server.services.library;

import com.streamarr.server.repositories.media.DeletionIntentEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MediaParentDeletionRetryWorker {

  private static final int BATCH_SIZE = 50;

  private final MediaParentDeletionTransactions transactions;
  private final MediaParentDeletionService deletionService;
  private DeletionIntentEntry libraryCursor;
  private DeletionIntentEntry mediaFileCursor;

  @Scheduled(fixedDelayString = "${streaming.parent-deletion-retry-interval-ms:15000}")
  public void retryPending() {
    libraryCursor = retryPage(libraryCursor, libraryLane());
    mediaFileCursor = retryPage(mediaFileCursor, mediaFileLane());
  }

  private DeletionIntentEntry retryPage(DeletionIntentEntry cursor, RetryLane lane) {
    List<DeletionIntentEntry> intents;
    try {
      intents = lane.pageLoader().apply(Optional.ofNullable(cursor));
    } catch (RuntimeException exception) {
      log.warn("{} deletion page could not be loaded", lane.subject(), exception);
      return cursor;
    }

    if (intents.isEmpty()) {
      return null;
    }
    for (var intent : intents) {
      try {
        lane.retry().accept(intent.id());
      } catch (RuntimeException exception) {
        log.warn("{} {} deletion will be retried", lane.subject(), intent.id(), exception);
      }
    }
    return intents.getLast();
  }

  private RetryLane libraryLane() {
    return new RetryLane(
        "Library",
        cursor ->
            cursor
                .map(value -> transactions.findPendingLibraryDeletionsAfter(value, BATCH_SIZE))
                .orElseGet(() -> transactions.findPendingLibraryDeletions(BATCH_SIZE)),
        deletionService::resumeLibraryDeletion);
  }

  private RetryLane mediaFileLane() {
    return new RetryLane(
        "Media file",
        cursor ->
            cursor
                .map(
                    value ->
                        transactions.findPendingStandaloneMediaFileDeletionsAfter(
                            value, BATCH_SIZE))
                .orElseGet(() -> transactions.findPendingStandaloneMediaFileDeletions(BATCH_SIZE)),
        deletionService::resumeMediaFileDeletion);
  }

  private record RetryLane(
      String subject,
      Function<Optional<DeletionIntentEntry>, List<DeletionIntentEntry>> pageLoader,
      Consumer<UUID> retry) {}
}
