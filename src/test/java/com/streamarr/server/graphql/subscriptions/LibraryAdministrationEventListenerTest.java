package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.dto.LibraryRemoval;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Library Administration Event Listener Tests")
class LibraryAdministrationEventListenerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final FakeLibraryRepository libraryRepository = new FakeLibraryRepository();
  private final LibraryAdministrationBroadcasters broadcasters =
      new LibraryAdministrationBroadcasters(
          meterRegistry, Schedulers.immediate(), new SubscriptionProperties(64, 32));
  private final LibraryAdministrationEventListener listener =
      new LibraryAdministrationEventListener(libraryRepository, broadcasters, meterRegistry);

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
              assertThat(payload).isNotSameAs(library);
            });
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
            new UnavailableLibraryRepository(), broadcasters, meterRegistry);

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

  private static final class UnavailableLibraryRepository extends FakeLibraryRepository {

    @Override
    public Optional<Library> findById(UUID id) {
      throw new DataAccessResourceFailureException("The database is unavailable.");
    }
  }
}
