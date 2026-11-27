package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPidRunning;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A share change is decided for every subscriber, so a Profile deleted while one subscriber's
 * decision reads it must not end that subscriber's operation.
 */
@Tag("IntegrationTest")
@DisplayName("Share Change Profile Deletion Race Integration Tests")
class ShareChangeProfileDeletionRaceIT extends AbstractIntegrationTest {

  private static final Duration BOUND = Duration.ofSeconds(10);

  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity stranger;

  @BeforeEach
  void createIdentities() {
    owner = authTestSupport.createIdentity();
    stranger = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteIdentities() {
    authTestSupport.deleteIdentity(stranger);
    authTestSupport.deleteIdentity(owner);
  }

  @Test
  @DisplayName(
      "Should give a stranger no change when the share's Profile is deleted while the change is"
          + " decided")
  void shouldGiveAStrangerNoChangeWhenTheSharesProfileIsDeletedWhileTheChangeIsDecided()
      throws Exception {
    var share = shareAtHome();
    var change = new ProfileShareChangedEvent(ChangedShare.of(share), true);
    var reader = authTestSupport.identityOf(stranger);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var observer = dataSource.getConnection();
        var deleter = openTransaction();
        var profileGate = openTransaction();
        var accountGate = openTransaction()) {
      // The deleter reads the Account table from the start, so its deletion's own reads of that
      // table go ahead of the account gate once the gate waits behind it.
      execute(deleter, "LOCK TABLE user_account IN ACCESS SHARE MODE");
      execute(profileGate, "LOCK TABLE profile IN ACCESS EXCLUSIVE MODE");
      var decided = executor.submit(() -> profileSharingService.shareChange(reader, change));
      awaitBlockedBackendPidRunning(observer, backendPid(profileGate), "%from profile%");

      // With the reader held at its first read of the Profile, the account gate queues on the
      // Account table, so the reader's next read of that table waits behind the gate.
      var accountGatePid = backendPid(accountGate);
      var gated =
          executor.submit(
              () -> {
                execute(accountGate, "LOCK TABLE user_account IN ACCESS EXCLUSIVE MODE");
                accountGate.commit();
                return null;
              });
      awaitBlockedBackendPidRunning(
          observer, backendPid(deleter), "LOCK TABLE user_account IN ACCESS EXCLUSIVE MODE");
      profileGate.commit();
      // A reader that holds its reads in one transaction already holds the Account table and
      // finishes; otherwise it waits behind the gate after it found the Profile.
      await()
          .atMost(BOUND)
          .until(
              () ->
                  decided.isDone()
                      || waitersBehind(observer, accountGatePid, "%server_admin%") == 1);

      deleteProfile(deleter, share.getProfileId());
      deleter.commit();

      assertThat(gated).succeedsWithin(BOUND);
      assertThat(decided).succeedsWithin(BOUND).isEqualTo(Optional.empty());
    }
  }

  /** A Profile the owner manages, available in the owner's Household through its active share. */
  private ProfileHouseholdShare shareAtHome() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(owner.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(profile.getId())
                  .build());
          return shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
        });
  }

  private Connection openTransaction() throws SQLException {
    var connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    return connection;
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void deleteProfile(Connection connection, UUID profileId) throws SQLException {
    try (var statement = connection.prepareStatement("DELETE FROM profile WHERE id = ?")) {
      statement.setObject(1, profileId);
      assertThat(statement.executeUpdate()).as("deleted Profile %s", profileId).isEqualTo(1);
    }
  }
}
