package com.streamarr.server.services.identity;

import static com.streamarr.server.support.AuditActorSetting.nameActorIn;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPidRunning;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A share's parties load after the page that lists the share, and the share can end in between. The
 * target Household's HouseholdAdmins see the Profile and the offerer only while the share is active
 * (ADR 0024), so nothing changed after the share ended may reach them.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Share Parties Snapshot Integration Tests")
class ProfileSharePartiesSnapshotIT extends AbstractIntegrationTest {

  private static final long BOUND_SECONDS = 10;

  @Autowired private ProfileSharePartiesService sharePartiesService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void createIdentities() {
    owner = authTestSupport.createIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteIdentities() {
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(owner);
  }

  @Test
  @DisplayName(
      "Should show the target HouseholdAdmin no Profile name given after the share ended when the"
          + " share ends while its Profile loads")
  void shouldShowTargetHouseholdAdminNoProfileNameGivenAfterShareEndedWhenShareEndsWhileItLoads()
      throws Exception {
    var profile = managedProfile();
    var readWhileActive = ShareReference.of(activeShareInto(host, profile));
    var reader = authTestSupport.identityOf(host);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var observer = dataSource.getConnection();
        var writer = openTransaction()) {
      execute(writer, "LOCK TABLE profile IN ACCESS EXCLUSIVE MODE");
      var loading =
          executor.submit(() -> sharePartiesService.profiles(reader, Set.of(readWhileActive)));
      awaitBlockedBackendPidRunning(observer, backendPid(writer), "%from profile p%");

      endShare(writer, readWhileActive.shareId());
      rename(writer, "UPDATE profile SET name = ? WHERE id = ?", profile.getId());
      writer.commit();

      assertThat(loading.get(BOUND_SECONDS, TimeUnit.SECONDS).values())
          .extracting(shared -> shared.profile().getName())
          .as("the Profile names the target HouseholdAdmin sees")
          .isSubsetOf(profile.getName());
    }
  }

  @Test
  @DisplayName(
      "Should show the target HouseholdAdmin no offerer name given after the share ended when the"
          + " share ends while its offerer loads")
  void shouldShowTargetHouseholdAdminNoOffererNameGivenAfterShareEndedWhenShareEndsWhileItLoads()
      throws Exception {
    var readWhileActive = ShareReference.of(activeShareInto(host, managedProfile()));
    var reader = authTestSupport.identityOf(host);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var observer = dataSource.getConnection();
        var writer = openTransaction()) {
      execute(writer, "LOCK TABLE user_account IN ACCESS EXCLUSIVE MODE");
      var loading =
          executor.submit(() -> sharePartiesService.offererNames(reader, Set.of(readWhileActive)));
      awaitBlockedBackendPidRunning(observer, backendPid(writer), "%user_account%");

      endShare(writer, readWhileActive.shareId());
      rename(
          writer, "UPDATE user_account SET display_name = ? WHERE id = ?", owner.account().getId());
      writer.commit();

      assertThat(loading.get(BOUND_SECONDS, TimeUnit.SECONDS).values())
          .as("the offerer names the target HouseholdAdmin sees")
          .isSubsetOf(owner.account().getDisplayName());
    }
  }

  private Profile managedProfile() {
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
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  /** The owner's active share of the Profile into the Household, as a page would read it. */
  private ProfileHouseholdShare activeShareInto(
      AuthTestSupport.TestIdentity into, Profile profile) {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profile.getId())
                    .householdId(into.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .offeredByAccountId(owner.account().getId())
                    .build()));
  }

  // The writer holds the table the load waits on, so the writer makes these changes itself.
  private static void endShare(Connection writer, UUID shareId) throws SQLException {
    nameActorIn(writer, SystemActor.ID.toString());
    try (var end =
        writer.prepareStatement(
            "UPDATE profile_household_share SET status = 'ENDED', ended_at = now()"
                + " WHERE id = ? AND status = 'ACTIVE'")) {
      end.setObject(1, shareId);
      assertThat(end.executeUpdate()).as("ended share %s", shareId).isEqualTo(1);
    }
  }

  private static void rename(Connection writer, String update, UUID id) throws SQLException {
    try (var rename = writer.prepareStatement(update)) {
      rename.setString(1, "Renamed-" + UUID.randomUUID());
      rename.setObject(2, id);
      assertThat(rename.executeUpdate()).as("renamed %s", id).isEqualTo(1);
    }
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
}
