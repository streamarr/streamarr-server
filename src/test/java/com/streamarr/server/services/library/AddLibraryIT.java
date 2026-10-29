package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import com.streamarr.server.support.PostgresLockTestSupport;
import com.streamarr.server.support.TestFixtureAuditActorConfiguration;
import com.streamarr.server.support.TestFixtureWork;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import javax.sql.DataSource;
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
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private CommittedLibraryRecorder committed;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<Path> libraryFolders = new ArrayList<>();
  private AuthTestSupport.TestIdentity testIdentity;

  // Deletes by path, so a library that an add committed after a failed assertion goes too.
  @AfterEach
  void deleteLibraries() {
    librariesAt(libraryFolders.toArray(Path[]::new)).forEach(libraryRepository::deleteById);
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
    var firstFolder = libraryFolder(adds.firstFolder());
    var secondFolder = libraryFolder(adds.secondFolder());
    Library first;
    Outcome<Library, AddLibraryRejection> second;
    // The held library is invisible to the first add's path check, so the first add's insert waits
    // for it while the first add holds its lock.
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var heldLibrary = HeldLibraryInsert.open(dataSource, firstFolder);
        var observer = dataSource.getConnection()) {
      var firstOutcome =
          executor.submit(
              () ->
                  libraryManagementService.addLibrary(
                      identity, unsavedLibrary("First", firstFolder)));
      var firstAddPid =
          PostgresLockTestSupport.awaitBlockedBackendPid(
              observer, heldLibrary.backendPid(), "transactionid");
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
                              jdbcTemplate, firstAddPid, "%pg_advisory_xact_lock%"))
                      .as("the second add waits for the first add's lock")
                      .isOne());
      heldLibrary.rollBack();
      first = accepted(firstOutcome.get(30, TimeUnit.SECONDS));
      second = secondOutcome.get(30, TimeUnit.SECONDS);
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
            new ConcurrentAdds("movies", "movies", AddLibraryRejection.PathAlreadyRegistered::new)),
        Arguments.of(
            "a path inside the first library's path",
            new ConcurrentAdds(
                "",
                "movies",
                (first, path) ->
                    new AddLibraryRejection.PathOverlap(LibraryPathOverlap.INSIDE, first, path))),
        Arguments.of(
            "a path that contains the first library's path",
            new ConcurrentAdds(
                "movies",
                "",
                (first, path) ->
                    new AddLibraryRejection.PathOverlap(
                        LibraryPathOverlap.CONTAINS, first, path))));
  }

  /** Two folders below the test's directory, and the refusal the second add expects. */
  record ConcurrentAdds(
      String firstFolder,
      String secondFolder,
      BiFunction<Library, Path, AddLibraryRejection> expectedRejection) {}

  /** An uncommitted library at one path, in a transaction of its own. Closing rolls it back. */
  private record HeldLibraryInsert(Connection connection, int backendPid) implements AutoCloseable {

    static HeldLibraryInsert open(DataSource dataSource, Path folder) throws SQLException {
      var connection = dataSource.getConnection();
      try {
        connection.setAutoCommit(false);
        TestFixtureAuditActorConfiguration.nameTestFixtureActorIn(connection);
        try (var insert =
            connection.prepareStatement(
                """
                INSERT INTO library (filepath_uri, name, status, backend, type)
                VALUES (?, 'Held library', 'HEALTHY', 'LOCAL', 'MOVIE')
                """)) {
          insert.setString(1, FilepathCodec.encode(folder));
          insert.executeUpdate();
        }

        return new HeldLibraryInsert(connection, PostgresLockTestSupport.backendPid(connection));
      } catch (SQLException failure) {
        connection.close();
        throw failure;
      }
    }

    void rollBack() throws SQLException {
      connection.rollback();
    }

    @Override
    public void close() throws SQLException {
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }

  private List<UUID> librariesAt(Path... folders) {
    var filepathUris = Arrays.stream(folders).map(FilepathCodec::encode).distinct().toList();
    return libraryRepository.findAll().stream()
        .filter(library -> filepathUris.contains(library.getFilepathUri()))
        .map(Library::getId)
        .toList();
  }

  private Path libraryFolder(String name) throws IOException {
    var folder = Files.createDirectories(tempDir.resolve(name));
    libraryFolders.add(folder);
    return folder;
  }

  private static Library accepted(Outcome<Library, AddLibraryRejection> outcome) {
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    return ((Outcome.Accepted<Library, AddLibraryRejection>) outcome).result();
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
