package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.util.unit.DataSize;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Library Administration Event Listener Tests")
class LibraryAdministrationEventListenerTest {

  private static final Duration BOUND = Duration.ofSeconds(5);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final FakeLibraryRepository libraryRepository = new FakeLibraryRepository();
  private final LibraryAdministrationBroadcasters broadcasters =
      new LibraryAdministrationBroadcasters(
          meterRegistry,
          Schedulers.immediate(),
          new SubscriptionProperties(64, 32, DataSize.ofMegabytes(1)));
  private final LibraryAdministrationEventListener listener =
      new LibraryAdministrationEventListener(
          libraryRepository, broadcasters, meterRegistry, new FakeTransactionManager());

  @Test
  @DisplayName("Should broadcast the current library when its status changes")
  void shouldBroadcastCurrentLibraryWhenStatusChanges() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    library.setStatus(LibraryStatus.SCANNING);
    var delivered = collect(broadcasters.getStatusChanges());

    listener.onLibraryStatusChanged(new LibraryStatusChangedEvent(library.getId()));

    assertThat(delivered)
        .singleElement()
        .satisfies(
            payload -> {
              assertThat(payload.getId()).isEqualTo(library.getId());
              assertThat(payload.getStatus()).isEqualTo(LibraryStatus.SCANNING);
            });
  }

  @Test
  @DisplayName("Should broadcast the library's audit timestamps when its status changes")
  void shouldBroadcastLibrarysAuditTimestampsWhenStatusChanges() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var delivered = collect(broadcasters.getStatusChanges());

    listener.onLibraryStatusChanged(new LibraryStatusChangedEvent(library.getId()));

    assertThat(delivered)
        .singleElement()
        .satisfies(
            payload -> {
              assertThat(payload.getCreatedOn()).isNotNull().isEqualTo(library.getCreatedOn());
              assertThat(payload.getLastModifiedOn())
                  .isNotNull()
                  .isEqualTo(library.getLastModifiedOn());
            });
  }

  @Test
  @DisplayName(
      "Should deliver the saved status last when an older publication pauses between its read and"
          + " broadcast")
  void shouldDeliverSavedStatusLastWhenOlderPublicationPausesBetweenReadAndBroadcast()
      throws Exception {
    var pausingRepository = new FirstReadPausingLibraryRepository();
    var orderedListener =
        new LibraryAdministrationEventListener(
            pausingRepository, broadcasters, meterRegistry, new FakeTransactionManager());
    var library = pausingRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var delivered = collect(broadcasters.getStatusChanges());
    var event = new LibraryStatusChangedEvent(library.getId());

    var olderPublication =
        Thread.ofPlatform().start(() -> orderedListener.onLibraryStatusChanged(event));
    pausingRepository.awaitFirstReadPaused();
    pausingRepository.save(library.toBuilder().status(LibraryStatus.SCANNING).build());
    var newerPublication =
        Thread.ofPlatform().start(() -> orderedListener.onLibraryStatusChanged(event));
    // A thread parked on a lock offers no callback, so the test polls the thread's state.
    await()
        .atMost(BOUND)
        .until(
            () ->
                newerPublication.getState() == Thread.State.WAITING || !newerPublication.isAlive());
    pausingRepository.releaseFirstRead();
    assertThat(olderPublication.join(BOUND)).isTrue();
    assertThat(newerPublication.join(BOUND)).isTrue();

    assertThat(delivered).last().extracting(Library::getStatus).isEqualTo(LibraryStatus.SCANNING);
  }

  @Test
  @DisplayName("Should broadcast nothing when the changed library no longer exists")
  void shouldBroadcastNothingWhenChangedLibraryNoLongerExists() {
    var delivered = collect(broadcasters.getStatusChanges());

    listener.onLibraryStatusChanged(new LibraryStatusChangedEvent(UUID.randomUUID()));

    assertThat(delivered).isEmpty();
  }

  @Test
  @DisplayName("Should broadcast the current library when a library is added")
  void shouldBroadcastCurrentLibraryWhenLibraryIsAdded() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var delivered = collect(broadcasters.getAdditions());

    listener.onLibraryAdded(new LibraryAddedEvent(library.getId(), library.getFilepathUri()));

    assertThat(delivered)
        .singleElement()
        .satisfies(
            payload -> {
              assertThat(payload.getId()).isEqualTo(library.getId());
              assertThat(payload.getName()).isEqualTo(library.getName());
            });
  }

  @Test
  @DisplayName("Should broadcast a removal receipt with the library id when a library is removed")
  void shouldBroadcastRemovalReceiptWithLibraryIdWhenLibraryIsRemoved() {
    var libraryId = UUID.randomUUID();
    var delivered = collect(broadcasters.getRemovals());

    listener.onLibraryRemoved(new LibraryRemovedEvent(libraryId, "file:///removed", Set.of()));

    assertThat(delivered).containsExactly(new LibraryRemoval(libraryId));
  }

  @Test
  @DisplayName("Should count a publication failure without throwing when the library cannot load")
  void shouldCountPublicationFailureWithoutThrowingWhenLibraryCannotBeLoaded() {
    var failingListener =
        new LibraryAdministrationEventListener(
            new UnavailableLibraryRepository(),
            broadcasters,
            meterRegistry,
            new FakeTransactionManager());

    assertThatCode(
            () ->
                failingListener.onLibraryStatusChanged(
                    new LibraryStatusChangedEvent(UUID.randomUUID())))
        .doesNotThrowAnyException();

    assertThat(
            meterRegistry
                .get(LibraryAdministrationEventListener.PUBLICATION_FAILURES_METRIC)
                .tag("field", "libraryStatusChanged")
                .counter()
                .count())
        .isEqualTo(1);
  }

  private static <T> List<T> collect(SubscriptionBroadcaster<T> broadcaster) {
    var delivered = new CopyOnWriteArrayList<T>();
    broadcaster.register().subscribe(delivered::add);
    return delivered;
  }

  // Its first read takes a copy of the saved row, then waits for the test before returning it.
  private static final class FirstReadPausingLibraryRepository extends FakeLibraryRepository {

    private final CountDownLatch firstReadPaused = new CountDownLatch(1);
    private final CountDownLatch firstReadReleased = new CountDownLatch(1);
    private final AtomicBoolean firstRead = new AtomicBoolean(true);

    @Override
    public Optional<Library> findById(UUID id) {
      Optional<Library> saved = super.findById(id).map(library -> library.toBuilder().build());
      if (firstRead.compareAndSet(true, false)) {
        firstReadPaused.countDown();
        awaitRelease();
      }

      return saved;
    }

    private void awaitRelease() {
      try {
        if (!firstReadReleased.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("The test never released the first read.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while the first read was paused.", e);
      }
    }

    private void awaitFirstReadPaused() throws InterruptedException {
      assertThat(firstReadPaused.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
    }

    private void releaseFirstRead() {
      firstReadReleased.countDown();
    }
  }

  private static final class UnavailableLibraryRepository extends FakeLibraryRepository {

    @Override
    public Optional<Library> findById(UUID id) {
      throw new DataAccessResourceFailureException("The database is unavailable.");
    }
  }
}
