package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The addLibrary write unit against real PostgreSQL: the unique index decides a duplicate-path
 * race, the loser is translated after rollback into a typed rejection with no side effect, and
 * Spring invokes the AFTER_COMMIT listener exactly once for the winner and never for a rollback.
 */
@Tag("IntegrationTest")
@DisplayName("Add Library Integration Tests")
@Import(AddLibraryIT.CommittedLibraryRecorder.class)
class AddLibraryIT extends AbstractIntegrationTest {

  private static final AuthenticatedIdentity IDENTITY = defaultIdentityBuilder().build();

  @TempDir Path tempDir;

  @Autowired private LibraryManagementService libraryManagementService;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private CommittedLibraryRecorder committed;

  private final List<UUID> createdLibraryIds = new ArrayList<>();

  @AfterEach
  void deleteLibraries() {
    createdLibraryIds.forEach(libraryRepository::deleteById);
    committed.events.clear();
  }

  @Test
  @DisplayName("Should let exactly one of two concurrent adds of the same path win")
  void shouldLetExactlyOneOfTwoConcurrentAddsOfSamePathWin() throws Exception {
    var start = new CountDownLatch(1);
    List<Future<Outcome<Library, AddLibraryRejection>>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(
              executor.submit(() -> addAfter(start, "First")),
              executor.submit(() -> addAfter(start, "Second")));
      start.countDown();
      for (var attempt : attempts) {
        attempt.get(30, TimeUnit.SECONDS);
      }
    }

    var accepted = new ArrayList<Library>();
    var rejections = new ArrayList<AddLibraryRejection>();
    for (var attempt : attempts) {
      switch (attempt.get()) {
        case Outcome.Accepted<Library, AddLibraryRejection>(var library) -> {
          accepted.add(library);
          createdLibraryIds.add(library.getId());
        }
        case Outcome.Rejected<Library, AddLibraryRejection>(var reasons) ->
            rejections.addAll(reasons);
      }
    }

    assertThat(accepted).hasSize(1);
    assertThat(rejections).containsExactly(new AddLibraryRejection.PathAlreadyRegistered());
    assertThat(libraryRepository.findAll())
        .filteredOn(library -> library.getFilepathUri().equals(FilepathCodec.encode(tempDir)))
        .hasSize(1);
    assertThat(committed.events)
        .as("AFTER_COMMIT ran once, for the winner, never for the rolled-back loser")
        .singleElement()
        .extracting(LibraryAddedEvent::libraryId)
        .isEqualTo(accepted.getFirst().getId());
  }

  private Outcome<Library, AddLibraryRejection> addAfter(CountDownLatch start, String name)
      throws InterruptedException {
    start.await();
    return libraryManagementService.addLibrary(IDENTITY, unsavedLibrary(name));
  }

  private Library unsavedLibrary(String name) {
    return Library.builder()
        .name(name)
        .filepathUri(tempDir.toString())
        .backend(LibraryBackend.LOCAL)
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }

  /** Registered by @Import as a bean of its own; Spring discovers the listener method on it. */
  @TestConfiguration(proxyBeanMethods = false)
  static class CommittedLibraryRecorder {

    final List<LibraryAddedEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCommitted(LibraryAddedEvent event) {
      events.add(event);
    }
  }
}
