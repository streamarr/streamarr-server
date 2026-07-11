package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Session Selection Revocation Race Integration Tests")
class SessionSelectionRevocationRaceIT extends AbstractIntegrationTest {

  private static final int ROUNDS = 40;

  @Autowired private SessionScopeService sessionScopeService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository householdMembershipRepository;

  @Autowired private AuthSessionRepository authSessionRepository;

  @Autowired private PlatformTransactionManager transactionManager;

  private UserAccount account;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      userAccountRepository.deleteById(account.getId());
    }
  }

  @Test
  @DisplayName("Should keep the session revoked when household selection races revocation")
  void shouldKeepSessionRevokedWhenHouseholdSelectionRacesRevocation() {
    account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());

    for (int round = 0; round < ROUNDS; round++) {
      var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
      householdMembershipRepository.grantMembership(
          HouseholdMembership.builder()
              .accountId(account.getId())
              .householdId(household.getId())
              .householdRole(HouseholdRole.OWNER)
              .build());
      var session =
          authSessionRepository.save(
              AuthSession.builder().accountId(account.getId()).deviceName("race-device").build());

      raceSelectionAgainstRevocation(session.getId(), household.getId());

      // Whichever thread wins the row, a committed revocation must survive: the selection's write
      // can never un-revoke the session. If it could, this reloads a null revokedAt.
      var reloaded = authSessionRepository.findById(session.getId()).orElseThrow();
      assertThat(reloaded.getRevokedAt()).isNotNull();
    }
  }

  private void raceSelectionAgainstRevocation(UUID sessionId, UUID householdId) {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var startLatch = new CountDownLatch(1);
      var doneLatch = new CountDownLatch(2);
      var errors = new CopyOnWriteArrayList<Throwable>();

      executor.submit(
          guarded(
              startLatch,
              doneLatch,
              errors,
              () -> sessionScopeService.selectHousehold(account.getId(), sessionId, householdId)));
      executor.submit(guarded(startLatch, doneLatch, errors, () -> revoke(sessionId)));

      startLatch.countDown();
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

      // Selection may lose the race and reject (the session was already revoked); nothing else
      // should fail — no deadlock, and revocation never fails.
      assertThat(errors).isEmpty();
    }
  }

  private void revoke(UUID sessionId) {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                authSessionRepository.revoke(
                    sessionId, SessionRevocationReason.LOGOUT, Instant.now()));
  }

  private Runnable guarded(
      CountDownLatch start,
      CountDownLatch done,
      CopyOnWriteArrayList<Throwable> errors,
      Runnable body) {
    return () -> {
      try {
        start.await();
        body.run();
      } catch (AuthenticationRequiredException _) {
        // Selection lost the race to revocation — expected, not an error.
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException e) {
        errors.add(e);
      } finally {
        done.countDown();
      }
    };
  }
}
