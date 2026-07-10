package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Household Membership Repository Integration Tests")
class HouseholdMembershipRepositoryIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Autowired private ProfileRepository profileRepository;

  @Autowired private AccountProfileRepository accountProfileRepository;

  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("Should advance version when membership revoked")
  void shouldAdvanceVersionWhenMembershipRevoked() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build();
    var granted = membershipRepository.grantMembership(membership);

    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    assertThat(revoked.version()).isGreaterThan(granted.version());
    assertThat(
            membershipRepository.findByAccountIdAndHouseholdId(account.getId(), household.getId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should advance revocation beyond profile grant and revoke")
  void shouldAdvanceRevocationBeyondProfileGrantAndRevoke() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build());
    var profile =
        profileRepository.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var link =
        AccountProfile.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .profileId(profile.getId())
            .build();
    accountProfileRepository.linkProfile(link);
    accountProfileRepository.revokeProfileLink(link);
    var versionAfterProfileChanges =
        membershipRepository
            .findByAccountIdAndHouseholdId(account.getId(), household.getId())
            .orElseThrow()
            .getMembershipVersion();

    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    assertThat(revoked.version()).isGreaterThan(versionAfterProfileChanges);
  }

  @Test
  @DisplayName("Should advance version when household role changed")
  void shouldAdvanceVersionWhenHouseholdRoleChanged() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var granted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());

    var roleChanged =
        membershipRepository
            .changeRole(
                HouseholdMembership.builder()
                    .accountId(account.getId())
                    .householdId(household.getId())
                    .householdRole(HouseholdRole.PARENT)
                    .build())
            .orElseThrow();

    assertThat(roleChanged.version()).isGreaterThan(granted.version());
    assertThat(
            membershipRepository
                .findByAccountIdAndHouseholdId(account.getId(), household.getId())
                .orElseThrow()
                .getHouseholdRole())
        .isEqualTo(HouseholdRole.PARENT);
  }

  @Test
  @DisplayName("Should not reuse version when membership revoked and regranted")
  void shouldNotReuseVersionWhenMembershipRevokedAndRegranted() {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var membership =
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build();
    var firstGrant = membershipRepository.grantMembership(membership);
    var revoked =
        membershipRepository.revokeMembership(account.getId(), household.getId()).orElseThrow();

    var regranted =
        membershipRepository.grantMembership(
            HouseholdMembership.builder()
                .accountId(account.getId())
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
                .build());

    assertThat(revoked.version()).isGreaterThan(firstGrant.version());
    assertThat(regranted.version()).isGreaterThan(revoked.version());
  }

  @Test
  @DisplayName("Should allocate regrant version after concurrent revocation commits")
  void shouldAllocateRegrantVersionAfterConcurrentRevocationCommits() throws Exception {
    var account = userAccountRepository.save(AccountFixture.defaultAccountBuilder().build());
    var household = householdRepository.save(HouseholdFixture.defaultHouseholdBuilder().build());
    membershipRepository.grantMembership(
        HouseholdMembership.builder()
            .accountId(account.getId())
            .householdId(household.getId())
            .householdRole(HouseholdRole.MEMBER)
            .build());
    installPausedRevokeTrigger();

    long sequenceWhileRevokePaused;
    long sequenceWhileGrantWaiting;
    boolean grantCompletedBeforeRevokeReleased;
    long revokedVersion;
    long regrantedVersion;
    try (var blocker = dataSource.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.createStatement().execute("SELECT pg_advisory_lock(2068675309)");
      try {
        var revoke =
            executor.submit(
                () ->
                    membershipRepository
                        .revokeMembership(account.getId(), household.getId())
                        .orElseThrow());
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(hasAdvisoryLockWaiter()).isTrue());
        sequenceWhileRevokePaused = currentMembershipSequenceValue();

        var regrant =
            executor.submit(
                () ->
                    membershipRepository.grantMembership(
                        HouseholdMembership.builder()
                            .accountId(account.getId())
                            .householdId(household.getId())
                            .householdRole(HouseholdRole.MEMBER)
                            .build()));
        await()
            .atMost(Duration.ofSeconds(10))
            .until(
                () ->
                    regrant.isDone()
                        || currentMembershipSequenceValue() != sequenceWhileRevokePaused
                        || lockWaiterCount() >= 2);
        sequenceWhileGrantWaiting = currentMembershipSequenceValue();
        grantCompletedBeforeRevokeReleased = regrant.isDone();

        blocker.createStatement().execute("SELECT pg_advisory_unlock(2068675309)");
        revokedVersion = revoke.get(10, TimeUnit.SECONDS).version();
        regrantedVersion = regrant.get(10, TimeUnit.SECONDS).version();
      } finally {
        blocker.createStatement().execute("SELECT pg_advisory_unlock(2068675309)");
      }
    } finally {
      dropPausedRevokeTrigger();
    }

    assertThat(grantCompletedBeforeRevokeReleased)
        .as("regrant must wait for the revocation transaction")
        .isFalse();
    assertThat(sequenceWhileGrantWaiting)
        .as("regrant must not reserve a version before revocation commits")
        .isEqualTo(sequenceWhileRevokePaused);
    assertThat(regrantedVersion).isGreaterThan(revokedVersion);
    assertThat(
            membershipRepository
                .findByAccountIdAndHouseholdId(account.getId(), household.getId())
                .orElseThrow()
                .getMembershipVersion())
        .isEqualTo(regrantedVersion);
  }

  private void installPausedRevokeTrigger() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE OR REPLACE FUNCTION pause_membership_revoke_for_test()
          RETURNS trigger
          LANGUAGE plpgsql
          AS $function$
          BEGIN
            PERFORM pg_advisory_xact_lock(2068675309);
            RETURN NEW;
          END;
          $function$
          """);
      statement.execute(
          """
          CREATE TRIGGER pause_membership_revoke_for_test
          BEFORE UPDATE ON household_membership
          FOR EACH ROW EXECUTE FUNCTION pause_membership_revoke_for_test()
          """);
    }
  }

  private boolean hasAdvisoryLockWaiter() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT EXISTS (
                  SELECT 1
                  FROM pg_stat_activity
                  WHERE datname = current_database()
                    AND wait_event_type = 'Lock'
                    AND wait_event = 'advisory'
                )
                """);
        var result = statement.executeQuery()) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private long lockWaiterCount() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock'
                """);
        var result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private long currentMembershipSequenceValue() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("SELECT last_value FROM household_membership_version_seq");
        var result = statement.executeQuery()) {
      result.next();
      return result.getLong(1);
    }
  }

  private void dropPausedRevokeTrigger() throws Exception {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "DROP TRIGGER IF EXISTS pause_membership_revoke_for_test ON household_membership");
      statement.execute("DROP FUNCTION IF EXISTS pause_membership_revoke_for_test()");
    }
  }
}
