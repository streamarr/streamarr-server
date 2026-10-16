package com.streamarr.server.services.library;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.events.library.LibraryAddedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.TestFixtureWork;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The addLibrary write unit against real PostgreSQL: concurrent adds wait on one lock before they
 * read the libraries, so the second add sees the first add's library and is refused with no side
 * effect. Spring invokes the AFTER_COMMIT listener exactly once, for the first add.
 */
@Tag("IntegrationTest")
@DisplayName("Add Library Integration Tests")
@Import(AddLibraryIT.CommittedLibraryRecorder.class)
class AddLibraryIT extends AbstractIntegrationTest {

  @TempDir Path tempDir;

  @Autowired private LibraryManagementService libraryManagementService;
  @MockitoSpyBean private LibraryRepository libraryRepository;
  @Autowired private CommittedLibraryRecorder committed;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdLibraryIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity testIdentity;

  @AfterEach
  void deleteLibraries() {
    createdLibraryIds.forEach(libraryRepository::deleteById);
    committed.events.clear();
    if (testIdentity != null) {
      authTestSupport.deleteIdentity(testIdentity);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("concurrentAdds")
  @DisplayName(
      "Should refuse the second add naming the first library when two adds of conflicting paths"
          + " run at once")
  void shouldRefuseSecondAddNamingFirstLibraryWhenTwoAddsOfConflictingPathsRunAtOnce(
      String description, ConcurrentAdds adds) throws Exception {
    testIdentity = authTestSupport.createAdminIdentity();
    var identity = authenticatedIdentity(testIdentity);
    var firstFolder = Files.createDirectories(tempDir.resolve(adds.firstFolder()));
    var secondFolder = Files.createDirectories(tempDir.resolve(adds.secondFolder()));
    var firstAdd = parkFirstAddBeforeItsInsert();
    Library first;
    Outcome<Library, AddLibraryRejection> second;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      try {
        var firstOutcome =
            executor.submit(
                () ->
                    libraryManagementService.addLibrary(
                        identity, unsavedLibrary("First", firstFolder)));
        awaitLatch(firstAdd.parked());
        var secondOutcome =
            executor.submit(
                () ->
                    libraryManagementService.addLibrary(
                        identity, unsavedLibrary("Second", secondFolder)));
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(
                            PostgresLockTestSupport.waitersBehind(
                                jdbcTemplate,
                                firstAdd.backendPid().get(),
                                "%pg_advisory_xact_lock%"))
                        .as("the second add waits for the first add's lock")
                        .isOne());
        firstAdd.release().countDown();
        first = accepted(firstOutcome.get(30, TimeUnit.SECONDS));
        second = secondOutcome.get(30, TimeUnit.SECONDS);
      } finally {
        firstAdd.release().countDown();
      }
    }

    assertThat(second)
        .isEqualTo(
            Outcome.rejected(adds.expectedRejection().apply(first, normalized(firstFolder))));
    assertThat(librariesAt(firstFolder, secondFolder)).containsExactly(first.getId());
    assertThat(committed.events)
        .as("AFTER_COMMIT ran once, for the first add, never for the refused add")
        .singleElement()
        .extracting(LibraryAddedEvent::libraryId)
        .isEqualTo(first.getId());
  }

  static Stream<Arguments> concurrentAdds() {
    return Stream.of(
        Arguments.of(
            "the same path",
            new ConcurrentAdds(
                "movies", "movies", AddLibraryRejection.PathAlreadyRegistered::new)));
  }

  /** Two folders below the test's directory, and the refusal the second add expects. */
  record ConcurrentAdds(
      String firstFolder,
      String secondFolder,
      BiFunction<Library, Path, AddLibraryRejection> expectedRejection) {}

  private record ParkedAdd(
      CountDownLatch parked, CountDownLatch release, AtomicInteger backendPid) {}

  /** Parks the first add inside its transaction, after its path check and before its insert. */
  private ParkedAdd parkFirstAddBeforeItsInsert() {
    var parkedAdd =
        new ParkedAdd(new CountDownLatch(1), new CountDownLatch(1), new AtomicInteger());
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);
    var parkedOnce = new AtomicBoolean();
    var repositorySpy = AopTestUtils.<LibraryRepository>getUltimateTargetObject(libraryRepository);
    var repositoryAnswer =
        mockingDetails(repositorySpy).getMockCreationSettings().getDefaultAnswer();
    doAnswer(
            invocation -> {
              if (parkedOnce.compareAndSet(false, true)) {
                parkedAdd.backendPid().set(lockProbe.currentBackendPid());
                parkedAdd.parked().countDown();
                awaitLatch(parkedAdd.release());
              }

              return repositoryAnswer.answer(invocation);
            })
        .when(repositorySpy)
        .saveAndFlush(any(Library.class));
    return parkedAdd;
  }

  private List<UUID> librariesAt(Path... folders) {
    var filepathUris = Arrays.stream(folders).map(FilepathCodec::encode).distinct().toList();
    return libraryRepository.findAll().stream()
        .filter(library -> filepathUris.contains(library.getFilepathUri()))
        .map(Library::getId)
        .toList();
  }

  private Library accepted(Outcome<Library, AddLibraryRejection> outcome) {
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var library = ((Outcome.Accepted<Library, AddLibraryRejection>) outcome).result();
    createdLibraryIds.add(library.getId());
    return library;
  }

  private static Path normalized(Path folder) {
    return folder.toAbsolutePath().normalize();
  }

  private static Library unsavedLibrary(String name, Path folder) {
    return Library.builder()
        .name(name)
        .filepathUri(folder.toString())
        .backend(LibraryBackend.LOCAL)
        .type(MediaType.MOVIE)
        .externalAgentStrategy(ExternalAgentStrategy.TMDB)
        .build();
  }

  private static AuthenticatedIdentity authenticatedIdentity(
      AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.PROFILE)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .profileId(identity.profile().getId())
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
