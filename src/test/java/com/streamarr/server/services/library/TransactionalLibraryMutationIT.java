package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.authorization.Intent;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Transactional Library Mutation Integration Tests")
class TransactionalLibraryMutationIT extends AbstractIntegrationTest {

  @Autowired private LibraryMutationTransaction libraryMutationTransaction;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private EntityManager entityManager;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  private final AtomicReference<UUID> libraryId = new AtomicReference<>();
  private AuthTestSupport.TestIdentity testIdentity;

  @AfterEach
  void deleteFixtures() {
    var savedLibraryId = libraryId.get();
    if (savedLibraryId != null && libraryRepository.existsById(savedLibraryId)) {
      libraryRepository.deleteById(savedLibraryId);
    }
    if (testIdentity != null) {
      authTestSupport.deleteIdentity(testIdentity);
    }
  }

  @Test
  @DisplayName("Should finish authorized add when ServerAdmin revocation runs concurrently")
  void shouldFinishAuthorizedAddWhenServerAdminRevocationRunsConcurrently() throws Exception {
    testIdentity = authTestSupport.createAdminIdentity();
    var identity = authenticatedIdentity(testIdentity);
    var mutationReached = new CountDownLatch(1);
    var releaseMutation = new CountDownLatch(1);
    var revocationTransactionStarted = new CountDownLatch(1);
    var revocationBackendPid = new AtomicInteger();
    var lockProbe = new PostgresLockProbe(entityManager, jdbcTemplate);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var add =
          executor.submit(
              () ->
                  libraryMutationTransaction.execute(
                      identity,
                      new Intent.AddLibrary(),
                      () -> saveLibraryAndHoldTransaction(mutationReached, releaseMutation)));
      assertThat(mutationReached.await(10, TimeUnit.SECONDS)).isTrue();

      var revocation =
          executor.submit(
              () -> {
                demoteToUser(revocationTransactionStarted, revocationBackendPid, lockProbe);
                return null;
              });
      assertThat(revocationTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(lockProbe.isUserAccountUpdateWaiting(revocationBackendPid.get()))
                      .isTrue());

      releaseMutation.countDown();
      assertThat(add.get(10, TimeUnit.SECONDS).getId()).isEqualTo(libraryId.get());
      revocation.get(10, TimeUnit.SECONDS);
    } finally {
      releaseMutation.countDown();
    }

    assertThat(libraryRepository.findById(libraryId.get())).isPresent();
    assertThat(userAccountRepository.findById(testIdentity.account().getId()).orElseThrow())
        .extracting(UserAccount::isServerAdmin)
        .isEqualTo(false);
  }

  private Library saveLibraryAndHoldTransaction(
      CountDownLatch mutationReached, CountDownLatch releaseMutation) {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    libraryId.set(library.getId());
    mutationReached.countDown();
    awaitLatch(releaseMutation, "ServerAdmin revocation did not observe the open add transaction");
    return library;
  }

  private void demoteToUser(
      CountDownLatch transactionStarted, AtomicInteger backendPid, PostgresLockProbe lockProbe) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account =
              userAccountRepository.findById(testIdentity.account().getId()).orElseThrow();
          backendPid.set(lockProbe.currentBackendPid());
          transactionStarted.countDown();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
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

  private static void awaitLatch(CountDownLatch latch, String failureMessage) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as(failureMessage).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while coordinating library mutation", e);
    }
  }
}
