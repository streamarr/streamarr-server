package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.LibraryOperationScheduler;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.services.events.library.ScanEndedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import com.streamarr.server.support.TestFixtureWork;
import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Library administration is a whole-surface gate decided by Cedar from the Account's live
 * ServerAdmin authority (ADR 0025): the token's role claim is routing and display only, so a
 * revoked admin is denied and a freshly granted one is allowed on the very next request. Denials
 * surface as the FORBIDDEN machine code.
 */
@Tag("IntegrationTest")
@DisplayName("Library Administration Integration Tests")
@Import(LibraryAdministrationIT.LibraryEventListenerConfig.class)
class LibraryAdministrationIT extends AbstractIntegrationTest {

  private static final long BOUND_SECONDS = 10;

  @TestConfiguration(proxyBeanMethods = false)
  static class LibraryEventListenerConfig {

    @Bean
    BlockingLibraryRemovalListener blockingLibraryRemovalListener() {
      return new BlockingLibraryRemovalListener();
    }

    @Bean
    ScanEndedListener scanEndedListener() {
      return new ScanEndedListener();
    }
  }

  static class ScanEndedListener {

    private final Map<UUID, CompletableFuture<Void>> endedScans = new ConcurrentHashMap<>();

    CompletableFuture<Void> scanEnded(UUID libraryId) {
      return endedScans.computeIfAbsent(libraryId, _ -> new CompletableFuture<>());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onScanEnded(ScanEndedEvent event) {
      scanEnded(event.libraryId()).complete(null);
    }
  }

  static class BlockingLibraryRemovalListener {

    private final AtomicReference<RemovalGate> gate = new AtomicReference<>();

    RemovalGate holdBeforeCommit() {
      var next = new RemovalGate(new CountDownLatch(1), new CountDownLatch(1));
      if (!gate.compareAndSet(null, next)) {
        throw new IllegalStateException("Library removal is already held.");
      }
      return next;
    }

    void release() {
      var current = gate.getAndSet(null);
      if (current != null) {
        current.release().countDown();
      }
    }

    @EventListener
    void onLibraryRemoved(LibraryRemovedEvent event) {
      var current = gate.get();
      if (current == null) {
        return;
      }
      current.reached().countDown();
      await(current.release());
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(BOUND_SECONDS, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Library removal gate was never released.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while holding library removal.", e);
      }
    }
  }

  record RemovalGate(CountDownLatch reached, CountDownLatch release) {}

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private BlockingLibraryRemovalListener blockingLibraryRemovalListener;

  @Autowired private EntityManager entityManager;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  @Autowired private ScanEndedListener scanEndedListener;

  @Autowired private LibraryOperationScheduler libraryOperationScheduler;

  @TempDir Path libraryDirectory;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    blockingLibraryRemovalListener.release();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mutation { removeLibrary(input: {libraryId: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\"})"
            + " { removedLibraryId } }",
        "mutation { startLibraryScan(input: {libraryId: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\"})"
            + " { library { id } } }",
        "mutation { startLibraryRefresh(input: {libraryId:"
            + " \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\"}) { library { id } } }",
        "mutation { addLibrary(input: {name: \\\"Denied\\\", filepath: \\\"file:///denied\\\","
            + " type: MOVIE, backend: LOCAL}) { library { id } } }"
      })
  @DisplayName("Should deny library administration when the Account is not a ServerAdmin")
  void shouldDenyLibraryAdministrationWhenAccountIsNotServerAdmin(String mutation)
      throws Exception {
    identity = authTestSupport.createIdentity();

    postGraphQl(mutation, authTestSupport.profileBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mutation { startLibraryScan(input: {libraryId: \\\"%s\\\"}) { library { id } } }",
        "mutation { startLibraryRefresh(input: {libraryId: \\\"%s\\\"}) { library { id } } }",
        "mutation { removeLibrary(input: {libraryId: \\\"%s\\\"}) { removedLibraryId } }",
        "mutation { addLibrary(input: {name: \\\"Denied\\\", filepath: \\\"file:///denied\\\","
            + " type: MOVIE, backend: LOCAL}) { library { id } } }"
      })
  @DisplayName(
      "Should refuse library administration with FORBIDDEN and leave the libraries unchanged when"
          + " the ServerAdmin's session is device-bound")
  void shouldRefuseLibraryAdministrationWhenServerAdminsSessionIsDeviceBound(String mutation)
      throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var librariesBefore = libraryRepository.count();

    try {
      postGraphQl(
              mutation.formatted(library.getId()),
              authTestSupport.deviceBoundProfileBearer(identity))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

      assertThat(libraryRepository.findById(library.getId()))
          .hasValueSatisfying(
              stored -> assertThat(stored.getStatus()).isEqualTo(library.getStatus()));
      assertThat(libraryRepository.count()).isEqualTo(librariesBefore);
      assertThat(libraryOperationsOf(library.getId())).isZero();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should preserve library when remove is denied")
  void shouldPreserveLibraryWhenRemoveDeniedForUserRole() throws Exception {
    identity = authTestSupport.createIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    try {
      postGraphQl(removeLibraryMutation(library.getId()), authTestSupport.profileBearer(identity))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should remove library when the Account is a ServerAdmin")
  void shouldRemoveLibraryWhenAccountIsServerAdmin() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    postGraphQl(removeLibraryMutation(library.getId()), authTestSupport.profileBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.removeLibrary.removedLibraryId").value(library.getId().toString()));

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
  }

  @Test
  @DisplayName(
      "Should return LibraryBusyError with the library's status when startLibraryRefresh meets a"
          + " running scan")
  void shouldReturnLibraryBusyErrorWithLibraryStatusWhenStartLibraryRefreshMeetsRunningScan()
      throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name("Busy Library")
                .filepathUri(FilepathCodec.encode(libraryDirectory))
                .status(LibraryStatus.HEALTHY)
                .build());
    var scanEnded = scanEndedListener.scanEnded(library.getId());

    // The stopped scheduler keeps the scan stored and the library SCANNING.
    libraryOperationScheduler.stop();
    try {
      postGraphQl(startLibraryScanMutation(library.getId()), adminToken)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.startLibraryScan.library.status").value("SCANNING"))
          .andExpect(jsonPath("$.data.startLibraryScan.userErrors").isEmpty());

      postGraphQl(startLibraryRefreshMutation(library.getId()), adminToken)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.startLibraryRefresh.library").doesNotExist())
          .andExpect(
              jsonPath("$.data.startLibraryRefresh.userErrors[0].__typename")
                  .value("LibraryBusyError"))
          .andExpect(
              jsonPath("$.data.startLibraryRefresh.userErrors[0].message")
                  .value("A library scan is already in progress."))
          .andExpect(jsonPath("$.data.startLibraryRefresh.userErrors[0].status").value("SCANNING"));
    } finally {
      libraryOperationScheduler.start();
    }

    try {
      scanEnded.get(BOUND_SECONDS, TimeUnit.SECONDS);
    } finally {
      postGraphQl(removeLibraryMutation(library.getId()), adminToken).andExpect(status().isOk());
    }
  }

  @Test
  @DisplayName("Should return the library's audit timestamps when a ServerAdmin starts a scan")
  void shouldReturnLibrarysAuditTimestampsWhenServerAdminStartsScan() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name("Audited Library")
                .filepathUri(FilepathCodec.encode(libraryDirectory))
                .status(LibraryStatus.HEALTHY)
                .build());
    var scanEnded = scanEndedListener.scanEnded(library.getId());

    // The stopped scheduler keeps the library's row as the scan's start left it.
    libraryOperationScheduler.stop();
    try {
      var response =
          postGraphQl(startLibraryScanWithAuditTimestampsMutation(library.getId()), adminToken);

      var returned = auditTimestampsOf(response, "$.data.startLibraryScan.library");
      var stored = libraryRepository.findById(library.getId()).orElseThrow();
      assertThat(returned.createdOn()).isEqualTo(stored.getCreatedOn());
      assertThat(returned.lastModifiedOn()).isEqualTo(stored.getLastModifiedOn());
    } finally {
      libraryOperationScheduler.start();
    }

    try {
      scanEnded.get(BOUND_SECONDS, TimeUnit.SECONDS);
    } finally {
      postGraphQl(removeLibraryMutation(library.getId()), adminToken).andExpect(status().isOk());
    }
  }

  @Test
  @DisplayName(
      "Should return the added library's audit timestamps when a ServerAdmin adds a library")
  void shouldReturnAddedLibrarysAuditTimestampsWhenServerAdminAddsLibrary() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);

    // The stopped scheduler keeps the library's row as the first scan's start left it.
    libraryOperationScheduler.stop();
    UUID libraryId;
    try {
      var content =
          postGraphQl(addLibraryWithAuditTimestampsMutation(libraryDirectory), adminToken)
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.errors").doesNotExist())
              .andExpect(jsonPath("$.data.addLibrary.library.status").value("SCANNING"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      libraryId = UUID.fromString(JsonPath.read(content, "$.data.addLibrary.library.id"));

      var returned = auditTimestampsOf(content, "$.data.addLibrary.library");
      var stored = libraryRepository.findById(libraryId).orElseThrow();
      assertThat(returned.createdOn()).isEqualTo(stored.getCreatedOn());
      assertThat(returned.lastModifiedOn()).isEqualTo(stored.getLastModifiedOn());
    } finally {
      libraryOperationScheduler.start();
    }

    try {
      scanEndedListener.scanEnded(libraryId).get(BOUND_SECONDS, TimeUnit.SECONDS);
    } finally {
      postGraphQl(removeLibraryMutation(libraryId), adminToken).andExpect(status().isOk());
    }
  }

  @Test
  @DisplayName(
      "Should refuse addLibrary with LibraryPathAlreadyRegisteredError naming the library when"
          + " another library uses the path")
  void shouldRefuseAddLibraryWithLibraryPathAlreadyRegisteredErrorWhenAnotherLibraryUsesPath()
      throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var registered = saveLibraryNamed("Audiobooks", libraryDirectory);

    try {
      postGraphQl(
              addLibraryWithUserErrorsMutation(libraryDirectory),
              authTestSupport.profileBearer(identity))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.addLibrary.library").doesNotExist())
          .andExpect(
              jsonPath("$.data.addLibrary.userErrors[0].__typename")
                  .value("LibraryPathAlreadyRegisteredError"))
          .andExpect(jsonPath("$.data.addLibrary.userErrors[0].inputPath[0]").value("filepath"))
          .andExpect(
              jsonPath("$.data.addLibrary.userErrors[0].conflictingLibrary.id")
                  .value(registered.getId().toString()))
          .andExpect(
              jsonPath("$.data.addLibrary.userErrors[0].conflictingLibrary.name")
                  .value("Audiobooks"))
          .andExpect(
              jsonPath("$.data.addLibrary.userErrors[0].conflictingPath")
                  .value(libraryDirectory.toAbsolutePath().normalize().toString()));

      assertThat(librariesAt(libraryDirectory)).containsExactly(registered.getId());
    } finally {
      libraryRepository.deleteById(registered.getId());
    }
  }

  @Test
  @DisplayName(
      "Should deny library administration when the token claims admin but the live row does not")
  void shouldDenyLibraryAdministrationWhenTokenClaimsAdminButLiveRowDoesNot() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    demoteToUser(identity);
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    try {
      postGraphQl(removeLibraryMutation(library.getId()), adminToken)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName(
      "Should allow library administration when the token claims user but the live row is admin")
  void shouldAllowLibraryAdministrationWhenTokenClaimsUserButLiveRowIsAdmin() throws Exception {
    identity = authTestSupport.createIdentity();
    var userToken = authTestSupport.profileBearer(identity);
    promoteToAdmin(identity);
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    postGraphQl(removeLibraryMutation(library.getId()), userToken)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.removeLibrary.removedLibraryId").value(library.getId().toString()));

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
  }

  @Test
  @DisplayName("Should deny library administration when the live ServerAdmin is disabled")
  void shouldDenyLibraryAdministrationWhenLiveServerAdminIsDisabled() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    disable(identity);
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    try {
      postGraphQl(removeLibraryMutation(library.getId()), adminToken)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

      assertThat(libraryRepository.existsById(library.getId())).isTrue();
    } finally {
      libraryRepository.deleteById(library.getId());
    }
  }

  @Test
  @DisplayName("Should finish authorized removal when ServerAdmin revocation runs concurrently")
  void shouldFinishAuthorizedRemovalWhenServerAdminRevocationRunsConcurrently() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var removalGate = blockingLibraryRemovalListener.holdBeforeCommit();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      try {
        var removal =
            executor.submit(
                () ->
                    postGraphQl(removeLibraryMutation(library.getId()), adminToken)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.errors").doesNotExist())
                        .andExpect(
                            jsonPath("$.data.removeLibrary.removedLibraryId")
                                .value(library.getId().toString())));
        assertThat(removalGate.reached().await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        var revocationTransactionStarted = new CountDownLatch(1);
        var revocationBackendPid = new AtomicInteger();
        var revocation =
            executor.submit(
                () -> {
                  demoteToUser(
                      identity, revocationTransactionStarted, revocationBackendPid, lockProbe);
                  return null;
                });
        assertThat(revocationTransactionStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        await()
            .atMost(Duration.ofSeconds(BOUND_SECONDS))
            .untilAsserted(
                () ->
                    assertThat(lockProbe.isUserAccountUpdateWaiting(revocationBackendPid.get()))
                        .isTrue());

        blockingLibraryRemovalListener.release();
        removal.get(BOUND_SECONDS, TimeUnit.SECONDS);
        revocation.get(BOUND_SECONDS, TimeUnit.SECONDS);
      } finally {
        blockingLibraryRemovalListener.release();
      }
    }

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
    assertThat(userAccountRepository.findById(identity.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(false);
  }

  @Test
  @DisplayName(
      "Should name the ServerAdmin as creator and the System actor as modifier when a ServerAdmin"
          + " adds a library")
  void shouldNameServerAdminAsCreatorAndSystemActorAsModifierWhenServerAdminAddsLibrary()
      throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);

    var response =
        postGraphQl(addLibraryMutation(libraryDirectory), adminToken)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
    var libraryId =
        UUID.fromString(
            JsonPath.read(
                response.getResponse().getContentAsString(), "$.data.addLibrary.library.id"));

    try {
      scanEndedListener.scanEnded(libraryId).get(BOUND_SECONDS, TimeUnit.SECONDS);

      var library = libraryRepository.findById(libraryId).orElseThrow();
      assertThat(library.getCreatedBy()).isEqualTo(identity.account().getId());
      assertThat(library.getLastModifiedBy()).isEqualTo(SystemActor.ID);
    } finally {
      postGraphQl(removeLibraryMutation(libraryId), adminToken).andExpect(status().isOk());
    }
  }

  private Library saveLibraryNamed(String name, Path directory) {
    return libraryRepository.saveAndFlush(
        LibraryFixtureCreator.unsavedLibraryBuilder()
            .name(name)
            .filepathUri(FilepathCodec.encode(directory))
            .status(LibraryStatus.HEALTHY)
            .build());
  }

  private List<UUID> librariesAt(Path directory) {
    return jdbcTemplate.queryForList(
        "SELECT id FROM library WHERE filepath_uri = ?",
        UUID.class,
        FilepathCodec.encode(directory));
  }

  private int libraryOperationsOf(UUID libraryId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM library_operation WHERE library_id = ?", Integer.class, libraryId);
  }

  private void demoteToUser(AuthTestSupport.TestIdentity identity) {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setServerAdmin(false);
    userAccountRepository.saveAndFlush(account);
  }

  private void demoteToUser(
      AuthTestSupport.TestIdentity identity,
      CountDownLatch transactionStarted,
      AtomicInteger backendPid,
      PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
          backendPid.set(lockProbe.currentBackendPid());
          transactionStarted.countDown();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private void promoteToAdmin(AuthTestSupport.TestIdentity identity) {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setServerAdmin(true);
    userAccountRepository.saveAndFlush(account);
  }

  private void disable(AuthTestSupport.TestIdentity identity) {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);
  }

  private static String addLibraryMutation(Path directory) {
    return ("mutation { addLibrary(input: {name: \\\"Added\\\", filepath: \\\"%s\\\","
            + " type: MOVIE, backend: LOCAL}) { library { id } } }")
        .formatted(directory.toAbsolutePath());
  }

  private static String addLibraryWithUserErrorsMutation(Path directory) {
    return ("mutation { addLibrary(input: {name: \\\"Added\\\", filepath: \\\"%s\\\","
            + " type: MOVIE, backend: LOCAL}) { library { id } userErrors { __typename"
            + " ... on InputMutationError { inputPath }"
            + " ... on LibraryPathAlreadyRegisteredError { conflictingLibrary { id name }"
            + " conflictingPath } } } }")
        .formatted(directory.toAbsolutePath());
  }

  private static String addLibraryWithAuditTimestampsMutation(Path directory) {
    return ("mutation { addLibrary(input: {name: \\\"Audited\\\", filepath: \\\"%s\\\","
            + " type: MOVIE, backend: LOCAL}) { library { id status createdOn lastModifiedOn } } }")
        .formatted(directory.toAbsolutePath());
  }

  private static String startLibraryScanWithAuditTimestampsMutation(UUID libraryId) {
    return ("mutation { startLibraryScan(input: {libraryId: \\\"%s\\\"}) { library { createdOn"
            + " lastModifiedOn } } }")
        .formatted(libraryId);
  }

  private static AuditTimestamps auditTimestampsOf(ResultActions response, String libraryPath)
      throws Exception {
    var content =
        response
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return auditTimestampsOf(content, libraryPath);
  }

  private static AuditTimestamps auditTimestampsOf(String content, String libraryPath) {
    return new AuditTimestamps(
        Instant.parse(JsonPath.read(content, libraryPath + ".createdOn")),
        Instant.parse(JsonPath.read(content, libraryPath + ".lastModifiedOn")));
  }

  private record AuditTimestamps(Instant createdOn, Instant lastModifiedOn) {}

  private static String startLibraryScanMutation(UUID libraryId) {
    return ("mutation { startLibraryScan(input: {libraryId: \\\"%s\\\"}) { library { status }"
            + " userErrors { __typename } } }")
        .formatted(libraryId);
  }

  private static String startLibraryRefreshMutation(UUID libraryId) {
    return ("mutation { startLibraryRefresh(input: {libraryId: \\\"%s\\\"}) { library { status }"
            + " userErrors { __typename ... on MutationError { message }"
            + " ... on LibraryBusyError { status } } } }")
        .formatted(libraryId);
  }

  private String removeLibraryMutation(UUID libraryId) {
    return ("mutation { removeLibrary(input: {libraryId: \\\"%s\\\"}) { removedLibraryId"
            + " userErrors { __typename } } }")
        .formatted(libraryId);
  }

  private ResultActions postGraphQl(String query, String token) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\": \"%s\"}".formatted(query))
            .with(bearer(token)));
  }
}
