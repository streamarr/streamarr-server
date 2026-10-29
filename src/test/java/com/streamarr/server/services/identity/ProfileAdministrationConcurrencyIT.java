package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.awaitBlockedBackendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.Builder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Profile Administration Concurrency Integration Tests")
class ProfileAdministrationConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private ProfileAdministrationService profileAdministrationService;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void deleteFixtures() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should reject an administrative PIN reset when ServerAdmin authority is revoked before the write")
  void shouldRejectAdministrativePinResetWhenServerAdminAuthorityIsRevokedBeforeWrite()
      throws Exception {
    var actor = identity(authTestSupport.createAdminIdentity());
    identity(authTestSupport.createAdminIdentity());
    var target = identity(authTestSupport.createIdentity());

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var targetLock = lockRow(rowOf("profile", target.profile().getId()));
        var observer = dataSource.getConnection()) {
      var reset =
          executor.submit(
              () ->
                  profileAdministrationService.administrativelyResetProfilePin(
                      authenticatedIdentity(actor),
                      target.profile().getId(),
                      "4242",
                      "account recovery"));
      awaitBlockedBackendPid(observer, targetLock.backendPid(), null);

      revokeServerAdmin(actor);
      targetLock.release();

      var rejections =
          reset
              .get(10, TimeUnit.SECONDS)
              .fold(
                  _ -> List.<ProfileRejections.AdministrativelyResetProfilePin>of(),
                  rejected -> rejected);
      assertThat(rejections).singleElement().isInstanceOf(ProfileRejections.ProfileNotFound.class);
    }

    assertThat(profileRepository.findById(target.profile().getId()).orElseThrow().getPinHash())
        .isNull();
  }

  @Test
  @DisplayName("Should finish an authorized rename before supervision is revoked concurrently")
  void shouldFinishAuthorizedRenameBeforeSupervisionIsRevokedConcurrently() throws Exception {
    var supervisor = identity(authTestSupport.createIdentity());
    var owner = identity(authTestSupport.createIdentity());
    var profile =
        accepted(
            profileAdministrationService.createProfile(
                authenticatedIdentity(owner),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(owner.household().getId())
                    .name("Shared Kid")
                    .kind(ProfileKind.KID)
                    .build()));
    var share =
        transactionTemplate.execute(
            _ ->
                shareRepository.saveAndFlush(
                    ProfileHouseholdShare.builder()
                        .profileId(profile.getId())
                        .householdId(supervisor.household().getId())
                        .status(ProfileShareStatus.ACTIVE)
                        .build()));

    var rename =
        commitBeforeRevocation(
            RevocationRace.builder()
                .committingHouseholdId(owner.household().getId())
                .authorizedOperation(
                    () ->
                        profileAdministrationService.renameProfile(
                            authenticatedIdentity(supervisor), profile.getId(), "Renamed Kid"))
                .revocation(() -> revokeSupervision(share.getId()))
                .revocationStatement("%delete%profile_household_share%")
                .build());

    assertThat(rename).isInstanceOf(Outcome.Accepted.class);
    assertThat(profileRepository.findById(profile.getId()).orElseThrow().getName())
        .isEqualTo("Renamed Kid");
    assertThat(shareRepository.findById(share.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should finish an authorized rename before management is revoked concurrently")
  void shouldFinishAuthorizedRenameBeforeManagementIsRevokedConcurrently() throws Exception {
    var manager = identity(authTestSupport.createIdentity());
    var homeAnchor = createEligibleAccountIn(manager.household().getId());
    var profile =
        accepted(
            profileAdministrationService.createProfile(
                authenticatedIdentity(manager),
                ProfileAdministrationService.CreateProfileCommand.builder()
                    .householdId(manager.household().getId())
                    .name("Managed Profile")
                    .build()));
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(homeAnchor.getId())
                    .profileId(profile.getId())
                    .build()));
    var relationship =
        profileManagerRepository.findByProfileId(profile.getId()).stream()
            .filter(candidate -> candidate.getAccountId().equals(manager.account().getId()))
            .findFirst()
            .orElseThrow();

    var rename =
        commitBeforeRevocation(
            RevocationRace.builder()
                .committingHouseholdId(manager.household().getId())
                .authorizedOperation(
                    () ->
                        profileAdministrationService.renameProfile(
                            authenticatedIdentity(manager), profile.getId(), "Still Managed"))
                .revocation(() -> revokeManagement(relationship.getId()))
                .revocationStatement("%delete%profile_manager%")
                .build());

    assertThat(rename).isInstanceOf(Outcome.Accepted.class);
    assertThat(profileRepository.findById(profile.getId()).orElseThrow().getName())
        .isEqualTo("Still Managed");
    assertThat(profileManagerRepository.findById(relationship.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should finish authorized creation before HouseholdAdmin authority is revoked")
  void shouldFinishAuthorizedCreationBeforeHouseholdAdminAuthorityIsRevoked() throws Exception {
    var actor = identity(authTestSupport.createIdentity());
    var remainingAdmin = createEligibleAccountIn(actor.household().getId());
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(remainingAdmin.getId()).orElseThrow();
          account.setHouseholdRole(HouseholdRole.ADMIN);
          userAccountRepository.saveAndFlush(account);
        });

    var creation =
        commitBeforeRevocation(
            RevocationRace.builder()
                .committingHouseholdId(actor.household().getId())
                .authorizedOperation(
                    () ->
                        profileAdministrationService.createProfile(
                            authenticatedIdentity(actor),
                            ProfileAdministrationService.CreateProfileCommand.builder()
                                .householdId(actor.household().getId())
                                .name("Concurrent Profile")
                                .build()))
                .revocation(() -> demoteHouseholdAdmin(actor))
                .revocationStatement("%update%user_account%")
                .build());

    accepted(creation);
    assertThat(
            profileRepository.findByHouseholdId(actor.household().getId()).stream()
                .map(Profile::getName))
        .contains("Concurrent Profile");
    assertThat(userAccountRepository.findById(actor.account().getId()).orElseThrow())
        .extracting(UserAccount::getHouseholdRole)
        .isEqualTo(HouseholdRole.MEMBER);
  }

  private UserAccount createEligibleAccountIn(UUID householdId) {
    return transactionTemplate.execute(
        _ -> {
          var personal =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(householdId)
                      .name("Home Anchor")
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(HouseholdRole.MEMBER)
                      .personalProfileId(personal.getId())
                      .passwordHash("test-only-hash")
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(personal.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  // A Profile write's commit locks its Household's guard, so holding the guard parks the authorized
  // operation after its write with every lock its authorization decision took.
  private Outcome<?, ?> commitBeforeRevocation(RevocationRace race) throws Exception {
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var guard = lockRow(householdGuardOf(race.committingHouseholdId()));
        var observer = dataSource.getConnection()) {
      var operation = executor.submit(race.authorizedOperation());
      var operationPid = awaitBlockedBackendPid(observer, guard.backendPid(), null);
      var revocation = executor.submit(race.revocation());
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(waitersBehind(jdbcTemplate, operationPid, race.revocationStatement()))
                      .as("the revocation waits for the authorized operation")
                      .isOne());

      guard.release();
      var outcome = operation.get(10, TimeUnit.SECONDS);
      revocation.get(10, TimeUnit.SECONDS);
      return outcome;
    }
  }

  private RowLockTarget householdGuardOf(UUID householdId) {
    return RowLockTarget.builder()
        .dataSource(dataSource)
        .table("household_guard")
        .keyColumn("household_id")
        .rowId(householdId)
        .build();
  }

  private RowLockTarget rowOf(String table, UUID rowId) {
    return RowLockTarget.builder().dataSource(dataSource).table(table).rowId(rowId).build();
  }

  private void revokeSupervision(UUID shareId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          shareRepository.deleteById(shareId);
          shareRepository.flush();
        });
  }

  private void revokeManagement(UUID relationshipId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.deleteById(relationshipId);
          profileManagerRepository.flush();
        });
  }

  // Only the role changes, so the UPDATE does not conflict with the KEY SHARE lock that the
  // creation's manager foreign key holds on the Account, and waits only on the authorization lock.
  private void demoteHouseholdAdmin(AuthTestSupport.TestIdentity actor) {
    transactionTemplate.executeWithoutResult(
        _ ->
            assertThat(userAccountRepository.tryDemoteToHouseholdMember(actor.account().getId()))
                .isTrue());
  }

  private void revokeServerAdmin(AuthTestSupport.TestIdentity actor) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(actor.account().getId()).orElseThrow();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private AuthTestSupport.TestIdentity identity(AuthTestSupport.TestIdentity identity) {
    identities.add(identity);
    return identity;
  }

  private static AuthenticatedIdentity authenticatedIdentity(
      AuthTestSupport.TestIdentity identity) {
    return AuthenticatedIdentity.builder()
        .accountId(identity.account().getId())
        .authSessionId(identity.session().getId())
        .scope(TokenScope.ACCOUNT)
        .householdId(identity.household().getId())
        .householdRole(identity.account().getHouseholdRole())
        .contextHouseholdId(identity.household().getId())
        .reauthenticatedAt(Optional.of(Instant.now()))
        .build();
  }

  private static <T, R> T accepted(Outcome<T, R> outcome) {
    return switch (outcome) {
      case Outcome.Accepted<T, R>(var result) -> result;
      case Outcome.Rejected<T, R>(var rejections) ->
          throw new AssertionError("Expected an accepted mutation but got: " + rejections);
    };
  }

  /**
   * An authorized operation that commits in {@code committingHouseholdId}, and the revocation of
   * the authority it was granted on, whose statement matches {@code revocationStatement} (ILIKE).
   */
  @Builder
  private record RevocationRace(
      UUID committingHouseholdId,
      Callable<Outcome<?, ?>> authorizedOperation,
      Runnable revocation,
      String revocationStatement) {}
}
