package com.streamarr.server.repositories.auth;

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
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import org.awaitility.Awaitility;
import org.hibernate.exception.ConstraintViolationException;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
 * The V056 invariants (T7 arrived with V059) as the database enforces them at commit (deferred
 * triggers, SQLSTATE 23514 with stable constraint names): each has a failing case that proves its
 * user impact and a passing case that proves legitimate transitions are not blocked.
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
    var failures = new ArrayList<RuntimeException>();
    for (var identity : identities) {
      try {
        authTestSupport.deleteIdentity(identity);
      } catch (RuntimeException failure) {
        failures.add(failure);
      }
    }

    identities.clear();
    if (failures.isEmpty()) {
      return;
    }

    var failure = failures.getFirst();
    failures.stream().skip(1).forEach(failure::addSuppressed);
    throw failure;
  }

  // ---- T1 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse removing the final Account when outside Household teardown (T1)")
  void shouldRefuseRemovingFinalAccountWhenOutsideHouseholdTeardown() {
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
  @DisplayName("Should refuse demotion when the Account is the last HouseholdAdmin (T1)")
  void shouldRefuseDemotionWhenAccountIsLastHouseholdAdmin() {
    var identity = create();
    var accountId = identity.account().getId();

    assertThatThrownBy(() -> demote(accountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_household_retains_admin");
  }

  @Test
  @DisplayName("Should keep exactly one HouseholdAdmin when two demotions race (T1)")
  void shouldKeepExactlyOneHouseholdAdminWhenTwoDemotionsRace() throws Exception {
    var first = create();
    var second = joinAsAdmin(first);
    var race =
        HouseholdAdminRace.builder()
            .updatesReady(new CountDownLatch(2))
            .commit(new CountDownLatch(1))
            .build();

    List<Future<Boolean>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(
              executor.submit(() -> demoteAfter(race, first.account().getId())),
              executor.submit(() -> demoteAfter(race, second.getId())));
      assertThat(race.updatesReady().await(30, TimeUnit.SECONDS)).isTrue();
      race.commit().countDown();
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
  @DisplayName("Should refuse a Personal Profile when its structural share is missing (T2)")
  void shouldRefusePersonalProfileWhenStructuralShareIsMissing() {
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
  @DisplayName("Should refuse ending a structural share when the Account remains a member (T3)")
  void shouldRefuseEndingStructuralShareWhenAccountRemainsMember() {
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
  @DisplayName(
      "Should refuse disabling an Account when it is the last enabled ServerAdmin after bootstrap (T4)")
  void shouldRefuseDisablingAccountWhenItIsLastEnabledServerAdminAfterBootstrap() {
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

  @Test
  @DisplayName("Should keep one enabled ServerAdmin when cross-Household demotions race (T4)")
  void shouldKeepOneEnabledServerAdminWhenCrossHouseholdDemotionsRace() throws Exception {
    var first = createServerAdmin();
    var second = createServerAdmin();
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, first.account().getId())
        .execute();
    var race =
        ServerAdminRace.builder()
            .updatesReady(new CountDownLatch(2))
            .validate(new CountDownLatch(1))
            .commit(new CountDownLatch(1))
            .build();

    List<Future<Boolean>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(
              executor.submit(() -> disableServerAdminAfter(race, first.account().getId())),
              executor.submit(() -> disableServerAdminAfter(race, second.account().getId())));
      assertThat(race.updatesReady().await(30, TimeUnit.SECONDS)).isTrue();
      race.validate().countDown();
      Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(blockedConstraintValidationCount()).isOne());
      race.commit().countDown();
      for (var attempt : attempts) {
        awaitServerAdminOutcome(attempt);
      }
    }

    assertThat(attempts.stream().filter(this::completed).count()).isEqualTo(1);
    assertThat(enabledServerAdminCount()).isEqualTo(1);
  }

  // ---- T5 ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse authority when the Personal Profile is restricted (T5)")
  void shouldRefuseAuthorityWhenPersonalProfileIsRestricted() {
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
      "Should allow restriction when a HouseholdAdmin directly manages the member (T5, T6)")
  void shouldAllowRestrictionWhenHouseholdAdminDirectlyManagesMember() {
    var admin = create();
    var member = join(admin, HouseholdRole.MEMBER);

    assertThatCode(() -> restrictUnderSupervision(admin, member)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject one write when a manager grant races Profile restriction (T5)")
  void shouldRejectOneWriteWhenManagerGrantRacesProfileRestriction() throws Exception {
    var recipientHome = create();
    var recipient = join(recipientHome, HouseholdRole.MEMBER);
    var targetHome = create();
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(recipientHome.account().getId())
                    .profileId(recipient.getPersonalProfileId())
                    .build()));
    var race =
        RestrictedAuthorityRace.builder()
            .updatesReady(new CountDownLatch(2))
            .validate(new CountDownLatch(1))
            .validationsStarted(new CountDownLatch(2))
            .firstValidationReady(new CountDownLatch(1))
            .commit(new CountDownLatch(1))
            .build();

    List<Future<Boolean>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(
              executor.submit(
                  () -> grantManagerAfter(race, recipient.getId(), targetHome.profile().getId())),
              executor.submit(
                  () -> restrictPersonalProfileAfter(race, recipient.getPersonalProfileId())));
      assertThat(race.updatesReady().await(30, TimeUnit.SECONDS)).isTrue();
      race.validate().countDown();
      assertThat(race.validationsStarted().await(30, TimeUnit.SECONDS)).isTrue();
      assertThat(race.firstValidationReady().await(30, TimeUnit.SECONDS)).isTrue();
      race.commit().countDown();
      for (var attempt : attempts) {
        awaitRestrictedAuthorityOutcome(attempt);
      }
    }

    assertThat(attempts.stream().filter(this::completed).count())
        .as("one conflicting write should fail after both constraint validations start")
        .isEqualTo(1);
    assertThat(
            profileRepository
                    .findById(recipient.getPersonalProfileId())
                    .orElseThrow()
                    .isRestricted()
                && profileManagerRepository.existsByAccountIdAndProfileId(
                    recipient.getId(), targetHome.profile().getId()))
        .isFalse();
  }

  @Test
  @DisplayName("Should refuse restriction when the member Account has no supervisor (T6)")
  void shouldRefuseRestrictionWhenMemberAccountHasNoSupervisor() {
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
      "Should refuse a Kid Profile when an eligible HouseholdAdmin manager is missing at home (T6)")
  void shouldRefuseKidProfileWhenEligibleHouseholdAdminManagerIsMissingAtHome() {
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
  @DisplayName("Should allow a Kid Profile when a HouseholdAdmin directly manages it (T6)")
  void shouldAllowKidProfileWhenHouseholdAdminDirectlyManagesIt() {
    var admin = create();

    assertThatCode(() -> createKid(admin)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName(
      "Should refuse manager removal when it would remove a Kid Profile's final eligible manager (T6)")
  void shouldRefuseManagerRemovalWhenItWouldRemoveKidProfilesFinalEligibleManager() {
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

  @Test
  @DisplayName(
      "Should refuse a manager move when it would move a Kid Profile's final eligible manager away (T6)")
  void shouldRefuseManagerMoveWhenItWouldMoveKidProfilesFinalEligibleManagerAway() {
    var firstAdmin = create();
    var firstKid = createKid(firstAdmin);
    var secondKid = createKid(create());

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var moving =
                          profileManagerRepository.findByProfileId(firstKid.getId()).getFirst();
                      moving.setProfileId(secondKid.getId());
                      profileManagerRepository.saveAndFlush(moving);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo("chk_profile_home_anchor");
  }

  @Test
  @DisplayName("Should reject a decline whose target is neither REJECTED nor CANCELED")
  void shouldRejectDeclineWhoseTargetIsNeitherRejectedNorCanceled() {
    var owner = create();
    var host = create();
    var offer =
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(owner.profile().getId())
                .householdId(host.household().getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());

    var offerId = offer.getId();
    var now = Instant.now();

    assertThatThrownBy(
            () -> shareRepository.tryDeclinePending(offerId, ProfileShareStatus.ACTIVE, now))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(shareRepository.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should fetch one page plus its lookahead when Profile shares are requested")
  void shouldFetchPagePlusLookaheadWhenProfileSharesAreRequested() {
    var owner = create();
    var firstHost = create();
    var secondHost = create();
    for (var host : List.of(firstHost, secondHost)) {
      shareRepository.saveAndFlush(
          ProfileHouseholdShare.builder()
              .profileId(owner.profile().getId())
              .householdId(host.household().getId())
              .status(ProfileShareStatus.PENDING)
              .expiresAt(Instant.now().plusSeconds(3600))
              .build());
    }

    var options =
        new KeysetPaginationOptions(
            null,
            PaginationOptions.builder()
                .paginationDirection(PaginationDirection.FORWARD)
                .cursor(Optional.empty())
                .limit(1)
                .build());

    var window = shareRepository.findByProfileId(owner.profile().getId(), options);

    assertThat(window)
        .hasSize(2)
        .isSortedAccordingTo(
            (left, right) -> left.getId().toString().compareTo(right.getId().toString()));
  }

  // ---- T8 / PIN ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Should refuse Profiles when available names differ only by case (T8)")
  void shouldRefuseProfilesWhenAvailableNamesDifferOnlyByCase() {
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
  @DisplayName("Should refuse a PIN hash when it is blank (effective-PIN rule)")
  void shouldRefusePinHashWhenBlank() {
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

  private boolean demoteAfter(HouseholdAdminRace race, UUID accountId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(accountId).orElseThrow();
          account.setHouseholdRole(HouseholdRole.MEMBER);
          userAccountRepository.saveAndFlush(account);
          race.updatesReady().countDown();
          try {
            assertThat(race.commit().await(30, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        });
    return true;
  }

  private boolean disableServerAdminAfter(ServerAdminRace race, UUID accountId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(accountId).orElseThrow();
          account.setServerAdmin(false);
          userAccountRepository.saveAndFlush(account);
          race.updatesReady().countDown();
          try {
            assertThat(race.validate().await(30, TimeUnit.SECONDS)).isTrue();
            dsl.execute("SET CONSTRAINTS chk_user_account_invariants IMMEDIATE");
            assertThat(race.commit().await(30, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        });
    return true;
  }

  private boolean grantManagerAfter(RestrictedAuthorityRace race, UUID accountId, UUID profileId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder().accountId(accountId).profileId(profileId).build());
          race.updatesReady().countDown();
          await(race.validate());
          race.validationsStarted().countDown();
          dsl.execute("SET CONSTRAINTS chk_profile_manager_invariants IMMEDIATE");
          race.firstValidationReady().countDown();
          await(race.commit());
        });
    return true;
  }

  private boolean restrictPersonalProfileAfter(RestrictedAuthorityRace race, UUID profileId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var profile = profileRepository.findById(profileId).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
          race.updatesReady().countDown();
          await(race.validate());
          race.validationsStarted().countDown();
          dsl.execute("SET CONSTRAINTS chk_profile_invariants IMMEDIATE");
          race.firstValidationReady().countDown();
          await(race.commit());
        });
    return true;
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  @Builder
  private record HouseholdAdminRace(CountDownLatch updatesReady, CountDownLatch commit) {}

  @Builder
  private record ServerAdminRace(
      CountDownLatch updatesReady, CountDownLatch validate, CountDownLatch commit) {}

  @Builder
  private record RestrictedAuthorityRace(
      CountDownLatch updatesReady,
      CountDownLatch validate,
      CountDownLatch validationsStarted,
      CountDownLatch firstValidationReady,
      CountDownLatch commit) {}

  /** Waits for the attempt; the losing demotion's constraint violation is the expected outcome. */
  private void awaitOutcome(Future<Boolean> attempt) throws InterruptedException, TimeoutException {
    try {
      attempt.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException expectedForLoser) {
      assertThat(constraintName(expectedForLoser)).isEqualTo("chk_household_retains_admin");
    }
  }

  private void awaitServerAdminOutcome(Future<Boolean> attempt)
      throws InterruptedException, TimeoutException {
    try {
      attempt.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException expectedForLoser) {
      assertThat(constraintName(expectedForLoser)).isEqualTo("chk_enabled_server_admin_remains");
    }
  }

  private void awaitRestrictedAuthorityOutcome(Future<Boolean> attempt)
      throws InterruptedException, TimeoutException {
    try {
      attempt.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException expectedForLoser) {
      assertThat(constraintName(expectedForLoser))
          .isEqualTo("chk_restricted_account_holds_no_authority");
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

  private long enabledServerAdminCount() {
    return userAccountRepository.findAll().stream()
        .filter(UserAccount::isEnabled)
        .filter(UserAccount::isServerAdmin)
        .count();
  }

  private int blockedConstraintValidationCount() {
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pg_stat_activity")
            .where(DSL.field("wait_event_type", String.class).eq("Lock"))
            .and(
                DSL.field("query", String.class)
                    .startsWithIgnoreCase(
                        "SET CONSTRAINTS chk_user_account_invariants IMMEDIATE")));
  }
}
