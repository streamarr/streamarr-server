package com.streamarr.server.repositories.auth;

import static com.streamarr.server.fixtures.PaginationFixture.firstSeek;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.pagination.ListOrder;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.SortDirection;
import com.streamarr.server.domain.pagination.SortValueKind;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import com.streamarr.server.support.ConstraintViolations;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
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
 * PostgreSQL enforcement of eligible administrators for hosted restricted Profiles, offer
 * invalidation details, and time-dependent share transitions. Account demotion and deletion tests
 * verify that the Household administrator constraint rejects the write before the hosting
 * supervision check can run.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Sharing Invariants Integration Tests")
@Import(AuthTestSupportConfig.class)
class ProfileSharingInvariantsIT extends AbstractIntegrationTest {

  private static final ListOrder NEWEST_FIRST =
      ListOrder.builder().valueKind(SortValueKind.INSTANT).direction(SortDirection.DESC).build();

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
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(CHK_INVALIDATION_REASON);
  }

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
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(CHK_INVALIDATION_REASON);
  }

  @Test
  @DisplayName("Should record an expired offer as expired when it is declined after expiry")
  void shouldRecordExpiredOfferAsExpiredWhenDeclinedAfterExpiry() {
    var owner = create();
    var host = create();
    var now = Instant.now();
    var offer = pendingOffer(owner, host, now.minusSeconds(60));

    var declined =
        transactionTemplate.execute(
            _ ->
                shareRepository.tryDeclinePending(offer.getId(), ProfileShareStatus.REJECTED, now));

    assertThat(declined)
        .hasValueSatisfying(share -> assertThat(share.shareId()).isEqualTo(offer.getId()));
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

    assertThat(invalidated).extracting(ChangedShare::shareId).containsExactly(live.getId());
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

    // The expired offer is recorded as expired, not invalidated.
    assertThat(invalidated).extracting(ChangedShare::shareId).containsExactly(expired.getId());
    var stored = shareRepository.findById(expired.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(stored.statusAt(now)).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(stored.getInvalidationReason()).isEmpty();
  }

  @Test
  @DisplayName("Should read an expired offer as expired when a Household's shares are filtered")
  void shouldReadExpiredOfferAsExpiredWhenHouseholdSharesAreFiltered() {
    var owner = create();
    var host = create();
    var now = Instant.now();
    var expired = pendingOffer(owner, host, now.minusSeconds(60));

    assertThat(sharesInto(host, now, ProfileShareStatus.PENDING)).isEmpty();
    assertThat(sharesInto(host, now, ProfileShareStatus.EXPIRED)).containsExactly(expired.getId());
  }

  @Test
  @DisplayName("Should keep only the named statuses when a Household's shares are filtered")
  void shouldKeepOnlyNamedStatusesWhenHouseholdSharesAreFiltered() {
    var owner = create();
    var host = create();
    var now = Instant.now();
    var byStatus = new EnumMap<ProfileShareStatus, UUID>(ProfileShareStatus.class);
    for (var status : ProfileShareStatus.values()) {
      byStatus.put(status, storedShare(createManagedAdultOrphan(owner), host, status, now));
    }

    var hostsOwnShare =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                host.profile().getId(), host.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow()
            .getId();
    for (var status : ProfileShareStatus.values()) {
      var expected =
          status == ProfileShareStatus.ACTIVE
              ? List.of(byStatus.get(status), hostsOwnShare)
              : List.of(byStatus.get(status));
      assertThat(sharesInto(host, now, status))
          .as(status.name())
          .containsExactlyInAnyOrderElementsOf(expected);
    }

    assertThat(sharesInto(host, now, ProfileShareStatus.PENDING, ProfileShareStatus.REJECTED))
        .containsExactlyInAnyOrder(
            byStatus.get(ProfileShareStatus.PENDING), byStatus.get(ProfileShareStatus.REJECTED));
    assertThat(sharesInto(host, now)).hasSize(ProfileShareStatus.values().length + 1);
  }

  @Test
  @DisplayName("Should keep only the named statuses when a Profile's shares are filtered")
  void shouldKeepOnlyNamedStatusesWhenProfileSharesAreFiltered() {
    var owner = create();
    var now = Instant.now();
    var profile = createManagedAdultOrphan(owner);
    var live = storedShare(profile, create(), ProfileShareStatus.PENDING, now);
    var expired = storedShare(profile, create(), ProfileShareStatus.EXPIRED, now);
    var stale = offerExpiringAt(profile, create(), now.minusSeconds(60));
    var rejected = storedShare(profile, create(), ProfileShareStatus.REJECTED, now);

    assertThat(sharesOf(profile, now, ProfileShareStatus.PENDING)).containsExactly(live);
    assertThat(sharesOf(profile, now, ProfileShareStatus.EXPIRED))
        .containsExactlyInAnyOrder(expired, stale);
    assertThat(sharesOf(profile, now, ProfileShareStatus.REJECTED, ProfileShareStatus.PENDING))
        .containsExactlyInAnyOrder(rejected, live);
    // Its home availability is a share too, so naming no status keeps one more.
    assertThat(sharesOf(profile, now)).hasSize(5);
  }

  // Hosting supervision checks and constraint evaluation order.

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
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(CHK_HOSTING_ADMIN);
  }

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
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(CHK_HOUSEHOLD_ADMIN);
  }

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
        .extracting(ConstraintViolations::constraintName)
        .isEqualTo(CHK_HOUSEHOLD_ADMIN);
  }

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

  /** A Kid Profile with an eligible HouseholdAdmin as its direct manager in its home Household. */
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

  private UUID offerExpiringAt(
      Profile profile, AuthTestSupport.TestIdentity host, Instant expiresAt) {
    return transactionTemplate
        .execute(
            _ ->
                shareRepository.saveAndFlush(
                    ProfileHouseholdShare.builder()
                        .profileId(profile.getId())
                        .householdId(host.household().getId())
                        .status(ProfileShareStatus.PENDING)
                        .expiresAt(expiresAt)
                        .build()))
        .getId();
  }

  /** A share stored in the status; a stored PENDING offer is still live at {@code now}. */
  private UUID storedShare(
      Profile profile, AuthTestSupport.TestIdentity host, ProfileShareStatus status, Instant now) {
    var base =
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(host.household().getId())
            .status(status)
            .expiresAt(now.plusSeconds(3600));
    var share =
        switch (status) {
          case ENDED -> base.endedAt(now);
          case INVALIDATED -> base.invalidationReason("offerer no longer authorized");
          case PENDING, ACTIVE, REJECTED, CANCELED, EXPIRED -> base.decidedAt(now);
        };

    return transactionTemplate.execute(_ -> shareRepository.saveAndFlush(share.build())).getId();
  }

  private List<UUID> sharesInto(
      AuthTestSupport.TestIdentity host, Instant now, ProfileShareStatus... statuses) {
    return ids(
        shareRepository.rowsIntoHousehold(
            host.household().getId(),
            new ShareStatusFilter(Set.of(statuses), now),
            firstSeek(NEWEST_FIRST, 20)));
  }

  private List<UUID> sharesOf(Profile profile, Instant now, ProfileShareStatus... statuses) {
    return ids(
        shareRepository.rowsOfProfile(
            profile.getId(),
            new ShareStatusFilter(Set.of(statuses), now),
            firstSeek(NEWEST_FIRST, 20)));
  }

  private static List<UUID> ids(List<Row<ProfileHouseholdShare>> rows) {
    return rows.stream().map(row -> row.item().getId()).toList();
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
}
