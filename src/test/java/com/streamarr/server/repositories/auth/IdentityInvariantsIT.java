package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.HouseholdGuard.HOUSEHOLD_GUARD;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.hibernate.exception.ConstraintViolationException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The V054 invariants as the database enforces them at commit (deferred triggers, SQLSTATE 23514
 * with stable constraint names): each has a failing case that proves its user impact and a passing
 * case that proves legitimate transitions are not blocked.
 */
@Tag("IntegrationTest")
@DisplayName("Identity Invariants Integration Tests")
@Import(AuthTestSupportConfig.class)
class IdentityInvariantsIT extends AbstractIntegrationTest {

  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    identities.forEach(authTestSupport::deleteIdentity);
  }

  // ---- T1 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse removing a Household's final Account outside teardown (T1)")
  void shouldRefuseRemovingHouseholdsFinalAccountOutsideTeardown() {
    var identity = create();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> userAccountRepository.deleteById(identity.account().getId())))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_household_retains_account");
  }

  @Test
  @DisplayName("Should refuse demoting the last HouseholdAdmin (T1)")
  void shouldRefuseDemotingLastHouseholdAdmin() {
    var identity = create();

    assertThatThrownBy(() -> demote(identity.account().getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_household_retains_admin");
  }

  @Test
  @DisplayName("Should keep exactly one HouseholdAdmin when two demotions race (T1)")
  void shouldKeepExactlyOneHouseholdAdminWhenTwoDemotionsRace() throws Exception {
    var first = create();
    var second = joinAsAdmin(first);
    var start = new CountDownLatch(1);

    List<Future<Boolean>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(
              executor.submit(() -> demoteAfter(start, first.account().getId())),
              executor.submit(() -> demoteAfter(start, second.getId())));
      start.countDown();
      for (var attempt : attempts) {
        awaitOutcome(attempt);
      }
    }

    var succeeded = attempts.stream().filter(this::completed).count();
    assertThat(succeeded).isEqualTo(1);
    assertThat(adminCount(first.household().getId())).isEqualTo(1);
  }

  // ---- T2 / T3 ----------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse an Account whose Personal Profile lacks its structural share (T2)")
  void shouldRefuseAccountWhosePersonalProfileLacksStructuralShare() {
    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var household =
                          householdRepository.saveAndFlush(
                              HouseholdFixture.defaultHouseholdBuilder().build());
                      var profile =
                          profileRepository.saveAndFlush(
                              ProfileFixture.defaultProfileBuilder()
                                  .householdId(household.getId())
                                  .build());
                      userAccountRepository.saveAndFlush(
                          AccountFixture.defaultAccountBuilder()
                              .householdId(household.getId())
                              .personalProfileId(profile.getId())
                              .build());
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_personal_profile_structural_share");
  }

  @Test
  @DisplayName("Should refuse ending a structural share while the Account remains a member (T3)")
  void shouldRefuseEndingStructuralShareWhileAccountRemainsMember() {
    var identity = create();
    var share =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                identity.profile().getId(), identity.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var ending = shareRepository.findById(share.getId()).orElseThrow();
                      ending.setStatus(ProfileShareStatus.ENDED);
                      ending.setEndedAt(Instant.now());
                      shareRepository.saveAndFlush(ending);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_structural_share_persists");
  }

  // ---- T4 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse disabling the last enabled ServerAdmin after bootstrap (T4)")
  void shouldRefuseDisablingLastEnabledServerAdminAfterBootstrap() {
    var admin = createServerAdmin();
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, admin.account().getId())
        .execute();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var account =
                          userAccountRepository.findById(admin.account().getId()).orElseThrow();
                      account.setEnabled(false);
                      userAccountRepository.saveAndFlush(account);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_enabled_server_admin_remains");
  }

  // ---- T5 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse authority for an Account whose Personal Profile is restricted (T5)")
  void shouldRefuseAuthorityForAccountWhosePersonalProfileIsRestricted() {
    var admin = create();
    var member = join(admin, HouseholdRole.MEMBER);
    restrictUnderSupervision(admin, member);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var account = userAccountRepository.findById(member.getId()).orElseThrow();
                      account.setHouseholdRole(HouseholdRole.ADMIN);
                      userAccountRepository.saveAndFlush(account);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_restricted_account_holds_no_authority");
  }

  @Test
  @DisplayName(
      "Should allow restricting a member once a HouseholdAdmin directly manages it (T5, T6)")
  void shouldAllowRestrictingMemberOnceHouseholdAdminDirectlyManagesIt() {
    var admin = create();
    var member = join(admin, HouseholdRole.MEMBER);

    assertThatCode(() -> restrictUnderSupervision(admin, member)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should refuse restricting a member Account nobody supervises (T6)")
  void shouldRefuseRestrictingMemberAccountNobodySupervises() {
    var admin = create();
    var member = join(admin, HouseholdRole.MEMBER);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var profile =
                          profileRepository.findById(member.getPersonalProfileId()).orElseThrow();
                      profile.setMaximumAllowedRatingAge(12);
                      profileRepository.saveAndFlush(profile);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_profile_home_anchor");
  }

  // ---- T6 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Should refuse a Kid Profile without an eligible HouseholdAdmin manager at home (T6)")
  void shouldRefuseKidProfileWithoutEligibleHouseholdAdminManagerAtHome() {
    var admin = create();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        profileRepository.saveAndFlush(
                            ProfileFixture.kidProfileBuilder()
                                .householdId(admin.household().getId())
                                .build())))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_profile_home_anchor");
  }

  @Test
  @DisplayName(
      "Should allow a Kid Profile anchored by a HouseholdAdmin who directly manages it (T6)")
  void shouldAllowKidProfileAnchoredByHouseholdAdminWhoDirectlyManagesIt() {
    var admin = create();

    assertThatCode(() -> createKid(admin)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should refuse removing the final eligible manager of a Kid Profile (T6)")
  void shouldRefuseRemovingFinalEligibleManagerOfKidProfile() {
    var admin = create();
    var kid = createKid(admin);

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        profileManagerRepository.deleteAll(
                            profileManagerRepository.findByProfileId(kid.getId()))))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_profile_home_anchor");
  }

  // ---- T8 / PIN ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse two available Profiles with the same name ignoring case (T8)")
  void shouldRefuseTwoAvailableProfilesWithSameNameIgnoringCase() {
    var identity = create();
    var visitor = create();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var renamed =
                          profileRepository.findById(visitor.profile().getId()).orElseThrow();
                      renamed.setName(identity.profile().getName().toUpperCase());
                      profileRepository.saveAndFlush(renamed);
                      shareRepository.saveAndFlush(
                          ProfileHouseholdShare.builder()
                              .profileId(visitor.profile().getId())
                              .householdId(identity.household().getId())
                              .status(ProfileShareStatus.ACTIVE)
                              .build());
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_household_profile_names_unique");
  }

  @Test
  @DisplayName("Should refuse a blank PIN hash (effective-PIN rule)")
  void shouldRefuseBlankPinHash() {
    var identity = create();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var profile =
                          profileRepository.findById(identity.profile().getId()).orElseThrow();
                      profile.setPinHash("   ");
                      profileRepository.saveAndFlush(profile);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_profile_pin_hash_not_blank");
  }

  // ---- guards -----------------------------------------------------------------------------------

  @Test
  @DisplayName("Should create a guard row with every Household and bump it on identity writes")
  void shouldCreateGuardRowWithEveryHouseholdAndBumpItOnIdentityWrites() {
    var identity = create();
    var before = guardVersion(identity.household().getId());

    join(identity, HouseholdRole.MEMBER);

    assertThat(before).isNotNull();
    assertThat(guardVersion(identity.household().getId())).isGreaterThan(before);
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private AuthTestSupport.TestIdentity create() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private AuthTestSupport.TestIdentity createServerAdmin() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    return identity;
  }

  /** A second Account joining the identity's Household (deleted with the Household). */
  private UserAccount join(AuthTestSupport.TestIdentity into, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var household = into.household();
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(household.getId())
                      .householdRole(role)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private UserAccount joinAsAdmin(AuthTestSupport.TestIdentity into) {
    return join(into, HouseholdRole.ADMIN);
  }

  private Profile createKid(AuthTestSupport.TestIdentity admin) {
    return transactionTemplate.execute(
        _ -> {
          var kid =
              profileRepository.saveAndFlush(
                  ProfileFixture.kidProfileBuilder()
                      .householdId(admin.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(kid.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(kid.getId())
                  .householdId(admin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return kid;
        });
  }

  /** Restricts the member's Personal Profile with the admin as direct manager (T6-valid). */
  private void restrictUnderSupervision(AuthTestSupport.TestIdentity admin, UserAccount member) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          // A restriction means supervision: the anchor becomes the admin manager.
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(member.getPersonalProfileId())
                  .build());
          var profile = profileRepository.findById(member.getPersonalProfileId()).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
        });
  }

  private void demote(UUID accountId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(accountId).orElseThrow();
          account.setHouseholdRole(HouseholdRole.MEMBER);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private boolean demoteAfter(CountDownLatch start, UUID accountId) throws InterruptedException {
    start.await();
    demote(accountId);
    return true;
  }

  /** Waits for the attempt; the losing demotion's constraint violation is the expected outcome. */
  private void awaitOutcome(Future<Boolean> attempt) throws InterruptedException, TimeoutException {
    try {
      attempt.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException expectedForLoser) {
      assertThat(constraintName(expectedForLoser)).isEqualTo("chk_household_retains_admin");
    }
  }

  private boolean completed(Future<Boolean> attempt) {
    try {
      return attempt.get();
    } catch (Exception _) {
      return false;
    }
  }

  /** The stable constraint name a deferred trigger raised, wherever the driver put it. */
  static String constraintName(Throwable failure) {
    Throwable cause = failure;
    while (cause != null) {
      if (cause instanceof ConstraintViolationException violation
          && violation.getConstraintName() != null) {
        return violation.getConstraintName();
      }
      if (cause instanceof PSQLException psql
          && psql.getServerErrorMessage() != null
          && psql.getServerErrorMessage().getConstraint() != null) {
        return psql.getServerErrorMessage().getConstraint();
      }
      cause = cause.getCause();
    }
    return null;
  }

  private long adminCount(UUID householdId) {
    return userAccountRepository.findAll().stream()
        .filter(account -> householdId.equals(account.getHouseholdId()))
        .filter(account -> account.getHouseholdRole() == HouseholdRole.ADMIN)
        .count();
  }

  private Long guardVersion(UUID householdId) {
    return dsl.select(HOUSEHOLD_GUARD.VERSION)
        .from(HOUSEHOLD_GUARD)
        .where(HOUSEHOLD_GUARD.HOUSEHOLD_ID.eq(householdId))
        .fetchOne(HOUSEHOLD_GUARD.VERSION);
  }
}
