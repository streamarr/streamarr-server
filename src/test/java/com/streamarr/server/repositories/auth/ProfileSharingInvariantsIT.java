package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Household;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sharing invariants PostgreSQL holds (T7 and the invalidation pairing) and the share
 * repository's time-aware transitions, judged against a real database. The T1-before-T7 cases pin
 * which constraint raises first, so the account-trigger arm of T7 is documented as dead defense.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Sharing Invariants Integration Tests")
@Import(AuthTestSupportConfig.class)
class ProfileSharingInvariantsIT extends AbstractIntegrationTest {

  private static final String CHK_INVALIDATION_REASON =
      "chk_profile_household_share_invalidation_reason";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_HOUSEHOLD_ADMIN = "chk_household_retains_admin";

  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<UUID> accountlessHouseholds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (var householdId : accountlessHouseholds) {
      transactionTemplate.executeWithoutResult(_ -> householdRepository.deleteById(householdId));
    }

    accountlessHouseholds.clear();
    for (var identity : identities) {
      authTestSupport.deleteIdentity(identity);
    }

    identities.clear();
  }

  // ---- I3: INVALIDATED pairs with a reason ------------------------------------------------------

  /** V060: an INVALIDATED share always records why. */
  @Test
  @DisplayName("Should refuse an invalidated share when its invalidation reason is missing")
  void shouldRefuseInvalidatedShareWhenInvalidationReasonIsMissing() {
    var owner = create();
    var host = create();
    var offer = pendingOffer(owner, host, Instant.now().plusSeconds(3600));

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> {
                      var share = shareRepository.findById(offer.getId()).orElseThrow();
                      share.setStatus(ProfileShareStatus.INVALIDATED);
                      share.setDecidedAt(Instant.now());
                      shareRepository.saveAndFlush(share);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo(CHK_INVALIDATION_REASON);
  }

  /** V060: only an INVALIDATED share carries a reason. */
  @Test
  @DisplayName("Should refuse a pending offer when it carries an invalidation reason")
  void shouldRefusePendingOfferWhenItCarriesInvalidationReason() {
    var owner = create();
    var host = create();

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ ->
                        shareRepository.saveAndFlush(
                            ProfileHouseholdShare.builder()
                                .profileId(owner.profile().getId())
                                .householdId(host.household().getId())
                                .status(ProfileShareStatus.PENDING)
                                .expiresAt(Instant.now().plusSeconds(3600))
                                .invalidationReason("offerer no longer authorized")
                                .build())))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo(CHK_INVALIDATION_REASON);
  }

  // ---- I1: expiry is a state, not a wish --------------------------------------------------------

  /** Declining an offer already past its expiry records EXPIRED, as replacement does. */
  @Test
  @DisplayName("Should record an expired offer as expired when it is declined after expiry")
  void shouldRecordExpiredOfferAsExpiredWhenDeclinedAfterExpiry() {
    var owner = create();
    var host = create();
    var now = Instant.now();
    var offer = pendingOffer(owner, host, now.minusSeconds(60));

    var declined =
        shareRepository.tryDeclinePending(offer.getId(), ProfileShareStatus.REJECTED, now);

    assertThat(declined).isTrue();
    assertThat(shareRepository.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.EXPIRED);
  }

  @Test
  @DisplayName(
      "Should invalidate only the offerer's unexpired pending offers when they lose authority")
  void shouldInvalidateOnlyOfferersUnexpiredPendingOffersWhenTheyLoseAuthority() {
    var owner = create();
    var other = create();
    var host = create();
    var now = Instant.now();
    var live = pendingOffer(owner, host, now.plusSeconds(3600));
    var expired = pendingOffer(owner, create(), now.minusSeconds(60));
    var othersOffer = pendingOffer(other, host, now.plusSeconds(3600));

    var invalidated =
        transactionTemplate.execute(
            _ ->
                shareRepository.invalidatePendingOfferedBy(
                    owner.account().getId(), "issuer disabled", now));

    assertThat(invalidated).isEqualTo(1);
    var stored = shareRepository.findById(live.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(stored.getInvalidationReason()).contains("issuer disabled");
    assertThat(shareRepository.findById(expired.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.PENDING);
    assertThat(shareRepository.findById(othersOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.PENDING);
  }

  @Test
  @DisplayName("Should materialize an expired offer when its Profile is invalidated")
  void shouldMaterializeExpiredOfferWhenProfileIsInvalidated() {
    var owner = create();
    var host = create();
    var now = Instant.now();
    var expired = pendingOffer(owner, host, now.minusSeconds(60));

    var invalidated =
        transactionTemplate.execute(
            _ ->
                shareRepository.invalidatePendingByProfileId(
                    owner.profile().getId(), "Profile linked to an Account", now));

    assertThat(invalidated).isZero();
    var stored = shareRepository.findById(expired.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(stored.statusAt(now)).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(stored.getInvalidationReason()).isEmpty();
  }

  @Test
  @DisplayName("Should omit an expired offer when a Household's pending offers are paged")
  void shouldOmitExpiredOfferWhenHouseholdPendingOffersArePaged() {
    var owner = create();
    var host = create();
    pendingOffer(owner, host, Instant.now().minusSeconds(60));

    var page =
        shareRepository.findPendingByHouseholdId(
            host.household().getId(), Instant.now(), firstPage(10));

    assertThat(page).isEmpty();
  }

  // ---- I6 / I2: which T7 arms are reachable -----------------------------------------------------

  /** The profile-trigger arm of T7: the path the policy changes translate. */
  @Test
  @DisplayName(
      "Should raise hosting supervision when a shared Profile becomes restricted in an accountless Household")
  void shouldRaiseHostingSupervisionWhenSharedProfileBecomesRestrictedInAccountlessHousehold() {
    var admin = create();
    var visitor = createManagedAdultOrphan(admin);
    var empty = createAccountlessHousehold();
    share(visitor.getId(), empty.getId());

    var visitorId = visitor.getId();

    assertThatThrownBy(() -> restrict(visitorId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo(CHK_HOSTING_ADMIN);
  }

  /** T1 fires before T7 in the same trigger loop: the demotion arm of T7 is dead defense. */
  @Test
  @DisplayName(
      "Should raise Household retains admin before hosting supervision when the last admin is demoted")
  void shouldRaiseHouseholdRetainsAdminBeforeHostingSupervisionWhenLastAdminIsDemoted() {
    var host = create();
    var other = create();
    var kid = createKid(other);
    share(kid.getId(), host.household().getId());

    var hostAccountId = host.account().getId();

    assertThatThrownBy(() -> demote(hostAccountId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo(CHK_HOUSEHOLD_ADMIN);
  }

  /** With a member remaining, deleting the sole admin trips T1 before T7. */
  @Test
  @DisplayName(
      "Should raise Household retains admin before hosting supervision when the last admin is deleted")
  void shouldRaiseHouseholdRetainsAdminBeforeHostingSupervisionWhenLastAdminIsDeleted() {
    var host = create();
    join(host, HouseholdRole.MEMBER);
    var other = create();
    var kid = createKid(other);
    share(kid.getId(), host.household().getId());

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    _ -> userAccountRepository.deleteById(host.account().getId())))
        .isInstanceOf(DataIntegrityViolationException.class)
        .extracting(IdentityInvariantsIT::constraintName)
        .isEqualTo(CHK_HOUSEHOLD_ADMIN);
  }

  /** An unrestricted share needs no supervising admin. */
  @Test
  @DisplayName("Should allow an accountless Household to host an unrestricted Profile")
  void shouldAllowAccountlessHouseholdToHostUnrestrictedProfile() {
    var admin = create();
    var visitor = createManagedAdultOrphan(admin);
    var empty = createAccountlessHousehold();

    assertThatCode(() -> share(visitor.getId(), empty.getId())).doesNotThrowAnyException();
  }

  // ---- helpers ----------------------------------------------------------------------------------

  private AuthTestSupport.TestIdentity create() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private Household createAccountlessHousehold() {
    var household =
        transactionTemplate.execute(
            _ ->
                householdRepository.saveAndFlush(
                    HouseholdFixture.defaultHouseholdBuilder().build()));
    accountlessHouseholds.add(household.getId());
    return household;
  }

  /** A second Account joining the identity's Household (deleted with the Household). */
  private UserAccount join(AuthTestSupport.TestIdentity into, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var householdId = into.household().getId();
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(householdId).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(role)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  /** A Kid at the admin's home with the admin as its direct manager (T6-valid). */
  private Profile createKid(AuthTestSupport.TestIdentity admin) {
    return createManagedOrphan(admin, ProfileFixture.kidProfileBuilder());
  }

  /** An unrestricted Adult orphan at the admin's home; the admin manager is its future anchor. */
  private Profile createManagedAdultOrphan(AuthTestSupport.TestIdentity admin) {
    return createManagedOrphan(admin, ProfileFixture.defaultProfileBuilder());
  }

  private Profile createManagedOrphan(
      AuthTestSupport.TestIdentity admin, Profile.ProfileBuilder<?, ?> profileBuilder) {
    return transactionTemplate.execute(
        _ -> {
          var householdId = admin.household().getId();
          var profile =
              profileRepository.saveAndFlush(profileBuilder.householdId(householdId).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  /** An ACTIVE visit (non-structural share), committed so the deferred triggers judge it. */
  private void share(UUID profileId, UUID householdId) {
    transactionTemplate.executeWithoutResult(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profileId)
                    .householdId(householdId)
                    .status(ProfileShareStatus.ACTIVE)
                    .build()));
  }

  private ProfileHouseholdShare pendingOffer(
      AuthTestSupport.TestIdentity owner, AuthTestSupport.TestIdentity host, Instant expiresAt) {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(owner.profile().getId())
                    .householdId(host.household().getId())
                    .status(ProfileShareStatus.PENDING)
                    .offeredByAccountId(owner.account().getId())
                    .expiresAt(expiresAt)
                    .build()));
  }

  private void restrict(UUID profileId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var profile = profileRepository.findById(profileId).orElseThrow();
          profile.setMaximumAllowedRatingAge(7);
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

  private static KeysetPaginationOptions firstPage(int limit) {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(limit)
            .build());
  }
}
