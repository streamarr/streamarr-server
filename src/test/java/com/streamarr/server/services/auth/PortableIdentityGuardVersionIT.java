package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Portable Identity Guard Version Integration Tests")
class PortableIdentityGuardVersionIT extends AbstractIntegrationTest {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;
  @Autowired private ProfileManagerRepository managerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagementService profileManagementService;
  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("Should keep profile management version monotonic after stale JPA update")
  void shouldKeepProfileManagementVersionMonotonicAfterStaleJpaUpdate() {
    var fixture = createFixture();
    var staleProfile = profileRepository.findById(fixture.profileId()).orElseThrow();
    var beforeConcurrentGuard = managementVersion(fixture.profileId());
    var secondManagerId = createAccount();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ ->
                managerRepository.save(
                    ProfileManager.builder()
                        .accountId(secondManagerId)
                        .profileId(fixture.profileId())
                        .build()));
    var afterConcurrentGuard = managementVersion(fixture.profileId());
    assertThat(afterConcurrentGuard).isGreaterThan(beforeConcurrentGuard);

    staleProfile.setName("Renamed From Stale Entity");
    profileRepository.saveAndFlush(staleProfile);

    assertThat(managementVersion(fixture.profileId())).isGreaterThanOrEqualTo(afterConcurrentGuard);
  }

  @Test
  @DisplayName("Should keep household safety version monotonic after stale JPA update")
  void shouldKeepHouseholdSafetyVersionMonotonicAfterStaleJpaUpdate() {
    var fixture = createFixture();
    var staleHousehold = householdRepository.findById(fixture.householdId()).orElseThrow();
    var beforeConcurrentGuard = safetyVersion(fixture.householdId());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            _ -> {
              var profile =
                  profileRepository.save(
                      Profile.builder().name("Second Shared Profile " + UUID.randomUUID()).build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(fixture.accountId())
                      .profileId(profile.getId())
                      .build());
              shareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(profile.getId())
                      .householdId(fixture.householdId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
            });
    var afterConcurrentGuard = safetyVersion(fixture.householdId());
    assertThat(afterConcurrentGuard).isGreaterThan(beforeConcurrentGuard);

    staleHousehold.setName("Renamed From Stale Entity");
    householdRepository.saveAndFlush(staleHousehold);

    assertThat(safetyVersion(fixture.householdId())).isGreaterThanOrEqualTo(afterConcurrentGuard);
  }

  @Test
  @DisplayName("Should not advance profile policy guard when only profile name changes")
  void shouldNotAdvanceProfilePolicyGuardWhenOnlyProfileNameChanges() {
    var fixture = createFixture();
    var profile = profileRepository.findById(fixture.profileId()).orElseThrow();
    var beforeRename = managementVersion(fixture.profileId());

    profile.setName("Renamed Profile");
    profileRepository.saveAndFlush(profile);

    assertThat(managementVersion(fixture.profileId())).isEqualTo(beforeRename);
  }

  @Test
  @DisplayName("Should enforce ordinary deletion invariants against final transaction state")
  void shouldEnforceOrdinaryDeletionInvariantsAgainstFinalTransactionState() {
    var fixture = createFixture();

    assertThatCode(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        _ -> {
                          shareRepository.deleteAll(
                              shareRepository.findByProfileId(fixture.profileId()));
                          shareRepository.flush();
                          deletionAuthorizationRepository.saveAndFlush(
                              ProfileDeletionAuthorization.builder()
                                  .profileId(fixture.profileId())
                                  .actingAccountId(fixture.accountId())
                                  .mode(ProfileDeletionMode.ORDINARY)
                                  .build());
                          managerRepository.deleteAll(
                              managerRepository.findByProfileId(fixture.profileId()));
                          managerRepository.flush();
                          profileRepository.deleteById(fixture.profileId());
                          profileRepository.flush();
                        }))
        .doesNotThrowAnyException();

    assertThat(profileRepository.existsById(fixture.profileId())).isFalse();
  }

  @Test
  @DisplayName("Should make repeated manager invitations idempotent")
  void shouldMakeRepeatedManagerInvitationsIdempotent() {
    var fixture = createFixture();
    var inviteeId = createAccount();
    var invite =
        ProfileManagerInvite.builder()
            .actingAccountId(fixture.accountId())
            .invitedAccountId(inviteeId)
            .profileId(fixture.profileId())
            .build();
    profileManagementService.invite(invite);

    assertThatCode(() -> profileManagementService.invite(invite)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should make repeated profile share offers idempotent")
  void shouldMakeRepeatedProfileShareOffersIdempotent() {
    var fixture = createFixture();
    var targetAccountId = createAccount();
    var targetHouseholdId =
        accountRepository.findById(targetAccountId).orElseThrow().getHomeHouseholdId();
    var offer =
        ProfileShareOffer.builder()
            .actingAccountId(fixture.accountId())
            .profileId(fixture.profileId())
            .targetHouseholdId(targetHouseholdId)
            .build();
    profileSharingService.offer(offer);

    assertThatCode(() -> profileSharingService.offer(offer)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject duplicate active profile names in one household")
  void shouldRejectDuplicateActiveProfileNamesInOneHousehold() {
    var fixture = createFixture();
    var existingName = profileRepository.findById(fixture.profileId()).orElseThrow().getName();
    var transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    _ -> {
                      var duplicate =
                          profileRepository.save(Profile.builder().name(existingName).build());
                      managerRepository.save(
                          ProfileManager.builder()
                              .accountId(fixture.accountId())
                              .profileId(duplicate.getId())
                              .build());
                      shareRepository.save(
                          ProfileHouseholdShare.builder()
                              .profileId(duplicate.getId())
                              .householdId(fixture.householdId())
                              .status(ProfileShareStatus.ACTIVE)
                              .build());
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should reject case-insensitive rename collision between active household profiles")
  void shouldRejectCaseInsensitiveRenameCollisionBetweenActiveHouseholdProfiles() {
    var fixture = createFixture();
    var existingName = profileRepository.findById(fixture.profileId()).orElseThrow().getName();
    var secondProfileId =
        new TransactionTemplate(transactionManager)
            .execute(
                _ -> {
                  var profile =
                      profileRepository.save(
                          Profile.builder().name("Distinct " + UUID.randomUUID()).build());
                  managerRepository.save(
                      ProfileManager.builder()
                          .accountId(fixture.accountId())
                          .profileId(profile.getId())
                          .build());
                  shareRepository.save(
                      ProfileHouseholdShare.builder()
                          .profileId(profile.getId())
                          .householdId(fixture.householdId())
                          .status(ProfileShareStatus.ACTIVE)
                          .build());
                  return profile.getId();
                });
    var transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    _ -> {
                      var profile = profileRepository.findById(secondProfileId).orElseThrow();
                      profile.setName(existingName.toUpperCase(Locale.ROOT));
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private Fixture createFixture() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Guard Home " + UUID.randomUUID()).build());
              var account = accountRepository.save(account(household.getId()));
              var profile =
                  profileRepository.save(
                      Profile.builder().name("Guard Profile " + UUID.randomUUID()).build());
              managerRepository.save(
                  ProfileManager.builder()
                      .accountId(account.getId())
                      .profileId(profile.getId())
                      .build());
              shareRepository.save(
                  ProfileHouseholdShare.builder()
                      .profileId(profile.getId())
                      .householdId(household.getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              return new Fixture(household.getId(), account.getId(), profile.getId());
            });
  }

  private UUID createAccount() {
    return new TransactionTemplate(transactionManager)
        .execute(
            _ -> {
              var household =
                  householdRepository.save(
                      Household.builder().name("Manager Home " + UUID.randomUUID()).build());
              return accountRepository.save(account(household.getId())).getId();
            });
  }

  private UserAccount account(UUID householdId) {
    return UserAccount.builder()
        .email("guard-version-" + UUID.randomUUID() + "@example.com")
        .displayName("Guard Version Account")
        .passwordHash("encoded")
        .accountRole(AccountRole.USER)
        .homeHouseholdId(householdId)
        .householdRole(HouseholdRole.OWNER)
        .build();
  }

  private long managementVersion(UUID profileId) {
    return jdbcTemplate.queryForObject(
        "SELECT management_version FROM profile WHERE id = ?", Long.class, profileId);
  }

  private long safetyVersion(UUID householdId) {
    return jdbcTemplate.queryForObject(
        "SELECT safety_version FROM household WHERE id = ?", Long.class, householdId);
  }

  private record Fixture(UUID householdId, UUID accountId, UUID profileId) {}
}
