package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.library.LibraryRemovedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockProbe;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Library administration is a whole-surface gate decided by Cedar from the Account's live
 * ServerAdmin authority (ADR 0025): the token's role claim is routing and display only, so a
 * revoked admin is denied and a freshly granted one is allowed on the very next request. Denials
 * surface as the FORBIDDEN machine code.
 */
@Tag("IntegrationTest")
@DisplayName("Library Administration Integration Tests")
@Import(LibraryAdministrationIT.BlockingLibraryRemovalConfig.class)
class LibraryAdministrationIT extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class BlockingLibraryRemovalConfig {

    @Bean
    BlockingLibraryRemovalListener blockingLibraryRemovalListener() {
      return new BlockingLibraryRemovalListener();
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
        if (!latch.await(10, TimeUnit.SECONDS)) {
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
        "mutation { removeLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
        "mutation { scanLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
        "mutation { refreshLibrary(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") }",
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
        .andExpect(jsonPath("$.data.removeLibrary").value(true));

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
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
        .andExpect(jsonPath("$.data.removeLibrary").value(true));

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
  @DisplayName("Should finish authorized removal before concurrent ServerAdmin revocation")
  void shouldFinishAuthorizedRemovalBeforeConcurrentServerAdminRevocation() throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var adminToken = authTestSupport.profileBearer(identity);
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var removalGate = blockingLibraryRemovalListener.holdBeforeCommit();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var removal =
            executor.submit(
                () ->
                    postGraphQl(removeLibraryMutation(library.getId()), adminToken)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.errors").doesNotExist())
                        .andExpect(jsonPath("$.data.removeLibrary").value(true)));
        assertThat(removalGate.reached().await(10, TimeUnit.SECONDS)).isTrue();

        var revocationTransactionStarted = new CountDownLatch(1);
        var revocationBackendPid = new AtomicInteger();
        var revocation =
            executor.submit(
                () -> {
                  demoteToUser(
                      identity, revocationTransactionStarted, revocationBackendPid, lockProbe);
                  return null;
                });
        assertThat(revocationTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();

        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(lockProbe.isUserAccountUpdateWaiting(revocationBackendPid.get()))
                        .isTrue());

        blockingLibraryRemovalListener.release();
        removal.get(10, TimeUnit.SECONDS);
        revocation.get(10, TimeUnit.SECONDS);
      } finally {
        blockingLibraryRemovalListener.release();
      }
    }

    assertThat(libraryRepository.existsById(library.getId())).isFalse();
    assertThat(userAccountRepository.findById(identity.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(false);
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

  private String removeLibraryMutation(UUID libraryId) {
    return "mutation { removeLibrary(id: \\\"%s\\\") }".formatted(libraryId);
  }

  private ResultActions postGraphQl(String query, String token) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\": \"%s\"}".formatted(query))
            .with(bearer(token)));
  }
}
