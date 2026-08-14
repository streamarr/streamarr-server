package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Deletion Authorization Race Integration Tests")
class PortableIdentityDeletionAuthorizationRaceIT extends AbstractIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should not silently delete a concurrently granted manager")
  void shouldNotSilentlyDeleteConcurrentlyGrantedManager() throws Exception {
    var fixture = createFixture();
    var managerWritten = new CountDownLatch(1);
    var deletionStarted = new CountDownLatch(1);
    try (var blockerConnection = dataSource.getConnection();
        var managerConnection = dataSource.getConnection();
        var deletionConnection = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      blockerConnection.setAutoCommit(false);
      managerConnection.setAutoCommit(false);
      deletionConnection.setAutoCommit(false);
      lockProfile(blockerConnection, fixture.profileId());
      var managerBackendPid = currentBackendPid(managerConnection);
      var deletionBackendPid = currentBackendPid(deletionConnection);
      var managerGrant =
          executor.submit(() -> grantManager(managerConnection, fixture, managerWritten));

      awaitLatch(managerWritten);
      awaitBlockedOnDatabaseLock(managerBackendPid);
      var deletion =
          executor.submit(() -> deleteProfile(deletionConnection, fixture, deletionStarted));

      awaitLatch(deletionStarted);
      awaitBlockedOnDatabaseLock(deletionBackendPid);
      blockerConnection.rollback();

      var managerFailure = managerGrant.get(30, TimeUnit.SECONDS);
      var deletionFailure = deletion.get(30, TimeUnit.SECONDS);
      assertThat(managerFailure == null && deletionFailure == null).isFalse();

      if (managerFailure == null) {
        assertThat(profileRepository.existsById(fixture.profileId())).isTrue();
        assertThat(
                managerRepository.existsByAccountIdAndProfileId(
                    fixture.newManagerId(), fixture.profileId()))
            .isTrue();
        assertThat(deletionFailure).isInstanceOf(SQLException.class);
        return;
      }

      assertThat(managerFailure).isInstanceOf(SQLException.class);
      assertThat(deletionFailure).isNull();
      assertThat(profileRepository.existsById(fixture.profileId())).isFalse();
    }
  }

  private Fixture createFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Deletion Race Home " + UUID.randomUUID()).build());
              var originalManager =
                  accountRepository.save(account(household.getId(), HouseholdRole.OWNER));
              var newManager =
                  accountRepository.save(account(household.getId(), HouseholdRole.PARENT));
              var profile =
                  profileRepository.save(
                      Profile.builder()
                          .name("Deletion Race Profile " + UUID.randomUUID())
                          .kind(ProfileKind.ADULT)
                          .build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(originalManager.getId())
                      .profileId(profile.getId())
                      .build());
              return new Fixture(profile.getId(), originalManager.getId(), newManager.getId());
            });
  }

  private UserAccount account(UUID householdId, HouseholdRole householdRole) {
    return UserAccount.builder()
        .email("deletion-race-" + UUID.randomUUID() + "@example.com")
        .displayName("Deletion Race Manager")
        .passwordHash("encoded")
        .accountRole(AccountRole.USER)
        .homeHouseholdId(householdId)
        .householdRole(householdRole)
        .build();
  }

  private void lockProfile(Connection connection, UUID profileId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "SELECT management_version FROM profile WHERE id = ? FOR NO KEY UPDATE")) {
      statement.setObject(1, profileId);
      statement.executeQuery();
    }
  }

  private Throwable grantManager(
      Connection connection, Fixture fixture, CountDownLatch managerWritten) {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO profile_manager (id, account_id, profile_id) VALUES (?, ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setObject(2, fixture.newManagerId());
      statement.setObject(3, fixture.profileId());
      statement.executeUpdate();
      managerWritten.countDown();
      connection.commit();
      return null;
    } catch (SQLException exception) {
      managerWritten.countDown();
      rollback(connection, exception);
      return exception;
    }
  }

  private Throwable deleteProfile(
      Connection connection, Fixture fixture, CountDownLatch deletionStarted) {
    deletionStarted.countDown();
    try (var authorization =
            connection.prepareStatement(
                "INSERT INTO profile_deletion_authorization (id, profile_id, acting_account_id, mode) VALUES (?, ?, ?, CAST(? AS profile_deletion_mode))");
        var deletion = connection.prepareStatement("DELETE FROM profile WHERE id = ?")) {
      authorization.setObject(1, UUID.randomUUID());
      authorization.setObject(2, fixture.profileId());
      authorization.setObject(3, fixture.originalManagerId());
      authorization.setString(4, "ORDINARY");
      authorization.executeUpdate();
      deletion.setObject(1, fixture.profileId());
      deletion.executeUpdate();
      connection.commit();
      return null;
    } catch (SQLException exception) {
      rollback(connection, exception);
      return exception;
    }
  }

  private int currentBackendPid(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
        var result = statement.executeQuery()) {
      result.next();
      return result.getInt(1);
    }
  }

  private void rollback(Connection connection, SQLException failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private void awaitBlockedOnDatabaseLock(int backendPid) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                Boolean.TRUE.equals(
                    jdbcTemplate.queryForObject(
                        "SELECT wait_event_type = 'Lock' FROM pg_stat_activity WHERE pid = ?",
                        Boolean.class,
                        backendPid)));
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent deletion race was interrupted", exception);
    }
  }

  private record Fixture(UUID profileId, UUID originalManagerId, UUID newManagerId) {}
}
