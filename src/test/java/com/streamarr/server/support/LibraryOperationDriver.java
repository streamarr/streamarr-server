package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.events.library.RefreshEndedEvent;
import com.streamarr.server.services.events.library.ScanCompletedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.library.LibraryOperationService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts a scan or a refresh through {@link LibraryOperationService} and returns once the
 * transaction that ends it has committed. The context's library-operation scheduler runs it.
 */
@RequiredArgsConstructor
public class LibraryOperationDriver {

  private static final Duration BOUND = Duration.ofSeconds(30);

  private final LibraryOperationService libraryOperationService;
  private final AuthTestSupport authTestSupport;
  private final Map<UUID, CompletableFuture<Void>> ends = new ConcurrentHashMap<>();
  private final Map<UUID, AtomicInteger> completedScans = new ConcurrentHashMap<>();

  /** Scans the library as the System actor and waits for the end. */
  public void scanToEnd(UUID libraryId) {
    scanToEnd(libraryId, BOUND);
  }

  /** Scans the library as the System actor and waits up to the bound for the end. */
  public void scanToEnd(UUID libraryId, Duration bound) {
    var end = nextEnd(libraryId);
    assertThat(libraryOperationService.startLibraryScanAsSystemActor(libraryId))
        .isInstanceOf(Outcome.Accepted.class);
    awaitEnd(end, bound);
  }

  /** Refreshes the library for a ServerAdmin and waits for the end. */
  public void refreshToEnd(UUID libraryId, ImageRefreshMode imageRefreshMode) {
    var admin = authTestSupport.createAdminIdentity();
    try {
      runToEnd(
          libraryId,
          () ->
              libraryOperationService.startLibraryRefresh(
                  authTestSupport.identityOf(admin), libraryId, imageRefreshMode));
    } finally {
      authTestSupport.deleteIdentity(admin);
    }
  }

  public void refreshToEnd(UUID libraryId) {
    refreshToEnd(libraryId, ImageRefreshMode.PRESERVE);
  }

  /**
   * Returns a future that completes once the library's next operation ends. Register it before the
   * operation starts.
   */
  public CompletableFuture<Void> nextEnd(UUID libraryId) {
    return ends.computeIfAbsent(libraryId, _ -> new CompletableFuture<>());
  }

  /** Waits for a future from {@link #nextEnd}. */
  public void awaitEnd(CompletableFuture<Void> end) {
    awaitEnd(end, BOUND);
  }

  private static void awaitEnd(CompletableFuture<Void> end, Duration bound) {
    try {
      end.get(bound.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for the library operation to end", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new AssertionError("The library operation did not end within " + bound, e);
    }
  }

  /** Counts the scans of the library that completed, from any thread, since the context started. */
  public int completedScans(UUID libraryId) {
    return completedScanCounter(libraryId).get();
  }

  private AtomicInteger completedScanCounter(UUID libraryId) {
    return completedScans.computeIfAbsent(libraryId, _ -> new AtomicInteger());
  }

  private void runToEnd(UUID libraryId, Supplier<Outcome<?, ?>> start) {
    var end = nextEnd(libraryId);
    assertThat(start.get()).isInstanceOf(Outcome.Accepted.class);
    awaitEnd(end);
  }

  @EventListener
  void onScanCompleted(ScanCompletedEvent event) {
    completedScanCounter(event.libraryId()).incrementAndGet();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onScanEnded(ScanEndedEvent event) {
    completeEnd(event.libraryId());
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onRefreshEnded(RefreshEndedEvent event) {
    completeEnd(event.libraryId());
  }

  private void completeEnd(UUID libraryId) {
    var end = ends.remove(libraryId);
    if (end != null) {
      end.complete(null);
    }
  }
}
