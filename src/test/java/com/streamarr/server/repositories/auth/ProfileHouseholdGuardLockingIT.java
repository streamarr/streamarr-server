package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Household Guard Locking Integration Tests")
class ProfileHouseholdGuardLockingIT extends AbstractIntegrationTest {

  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DataSource dataSource;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteFixtures() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should share Household guards when recording a Profile selection")
  void shouldShareHouseholdGuardsWhenRecordingProfileSelection() throws Exception {
    var source = identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    activeShare(source.profile().getId(), target.household().getId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var guard = holdSharedHouseholdGuard(target.household().getId())) {
      var selectionLock =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ -> {
                        assertThat(profileRepository.lockById(source.profile().getId())).isTrue();
                        profileRepository.lockProfileAvailabilityAcrossHouseholds(
                            source.profile().getId());
                      }));

      assertThat(selectionLock.get(5, TimeUnit.SECONDS)).isNull();
    }
  }

  @Test
  @DisplayName("Should exclusively lock a pending Share Household for a Profile transition")
  void shouldExclusivelyLockPendingShareHouseholdForProfileTransition() throws Exception {
    var source = identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    pendingShare(source.profile().getId(), target.household().getId(), source.account().getId());

    assertTransitionWaitsForSharedGuard(
        source.profile().getId(), target.household().getId(), List.of());
  }

  @Test
  @DisplayName("Should exclusively lock an explicit reoffer Household for a Profile transition")
  void shouldExclusivelyLockExplicitReofferHouseholdForProfileTransition() throws Exception {
    var source = identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());

    assertTransitionWaitsForSharedGuard(
        source.profile().getId(), target.household().getId(), List.of(target.household().getId()));
  }

  @Test
  @DisplayName("Should exclusively lock an ended Share Household before Profile deletion")
  void shouldExclusivelyLockEndedShareHouseholdBeforeProfileDeletion() throws Exception {
    var source = identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());
    endedShare(source.profile().getId(), target.household().getId());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var guard = holdSharedHouseholdGuard(target.household().getId())) {
      var blockerPid = backendPid(guard);
      var deletionLock =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ -> {
                        assertThat(profileRepository.lockById(source.profile().getId())).isTrue();
                        profileRepository.lockProfileDeletionAcrossHouseholds(
                            source.profile().getId());
                      }));

      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);
      guard.rollback();
      assertThat(deletionLock.get(5, TimeUnit.SECONDS)).isNull();
    }
  }

  private void assertTransitionWaitsForSharedGuard(
      UUID profileId, UUID householdId, List<UUID> additionalHouseholdIds) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var guard = holdSharedHouseholdGuard(householdId)) {
      var blockerPid = backendPid(guard);
      var transitionLock =
          executor.submit(
              () ->
                  transactions.executeWithoutResult(
                      _ -> {
                        assertThat(profileRepository.lockById(profileId)).isTrue();
                        profileRepository.lockProfileTransitionAcrossHouseholds(
                            profileId, additionalHouseholdIds);
                      }));

      await().atMost(Duration.ofSeconds(5)).until(() -> blockedConnectionCount(blockerPid) == 1);
      guard.rollback();
      assertThat(transitionLock.get(5, TimeUnit.SECONDS)).isNull();
    }
  }

  private void activeShare(UUID profileId, UUID householdId) {
    saveShare(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private void pendingShare(UUID profileId, UUID householdId, UUID offererAccountId) {
    saveShare(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(offererAccountId)
            .expiresAt(Instant.now().plus(Duration.ofDays(7)))
            .build());
  }

  private void endedShare(UUID profileId, UUID householdId) {
    saveShare(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.ENDED)
            .decidedAt(Instant.now())
            .endedAt(Instant.now())
            .build());
  }

  private void saveShare(ProfileHouseholdShare share) {
    transactions.executeWithoutResult(_ -> shareRepository.saveAndFlush(share));
  }

  private Connection holdSharedHouseholdGuard(UUID householdId) throws Exception {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (var statement =
        connection.prepareStatement(
            "SELECT household_id FROM household_guard WHERE household_id = ? FOR SHARE")) {
      statement.setObject(1, householdId);
      statement.executeQuery().close();
    } catch (Exception failure) {
      connection.close();
      throw failure;
    }

    return connection;
  }

  private int blockedConnectionCount(int blockerPid) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT count(*)
                FROM pg_stat_activity
                WHERE ? = ANY(pg_blocking_pids(pid))
                """)) {
      statement.setInt(1, blockerPid);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getInt(1);
      }
    } catch (Exception failure) {
      throw new AssertionError("could not inspect PostgreSQL lock state", failure);
    }
  }

  private static int backendPid(Connection connection) {
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      return result.getInt(1);
    } catch (Exception failure) {
      throw new AssertionError("could not identify PostgreSQL connection", failure);
    }
  }

  private AuthTestSupport.TestIdentity identity(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
