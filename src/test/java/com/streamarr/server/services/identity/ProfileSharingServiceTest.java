package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.ProfileHouseholdShareFixture.activeShareBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The sharing lifecycle over fakes: one winner per transition, the oracle rule per seat, and the
 * unshare side effects — selections cleared, a visitor's sessions dropped home.
 */
@Tag("UnitTest")
@DisplayName("Profile Sharing Service Tests")
class ProfileSharingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
  private static final Duration INVITATION_TTL = Duration.ofDays(2);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private Household household;
  private Profile profile;
  private ProfileSharingService service;

  @BeforeEach
  void setUp() {
    service =
        new ProfileSharingService(
            authorization,
            shares,
            profiles,
            households,
            accounts,
            sessions,
            audit,
            new MutationTransactions(
                new FakeTransactionManager(), new ConstraintViolationTranslator()),
            CredentialCodeProperties.builder()
                .invitationTtl(INVITATION_TTL)
                .passwordResetTtl(Duration.ofHours(1))
                .replacementLockTimeout(Duration.ofSeconds(5))
                .build(),
            new PaginationService(),
            CLOCK);
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    profile = profiles.save(ProfileFixture.defaultProfileBuilder().build());
  }

  @Test
  @DisplayName(
      "Should create a pending offer with its offerer and expiry when an offer is authorized")
  void shouldCreatePendingOfferWithOffererAndExpiryWhenOfferIsAuthorized() {
    var offered = service.offerProfileShare(identity(), profile.getId(), household.getId());

    assertThat(offered).isInstanceOf(Outcome.Accepted.class);
    var share =
        offered.fold(
            value -> value,
            rejections -> {
              throw new AssertionError("expected an accepted offer but got " + rejections);
            });
    assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
    assertThat(share.getOfferedByAccountId()).isEqualTo(identity().accountId());
    assertThat(share.getExpiresAt()).isEqualTo(NOW.plus(INVITATION_TTL));
  }

  @Test
  @DisplayName("Should return not-found when offer targets are hidden by the oracle rule")
  void shouldReturnNotFoundWhenOfferTargetsAreHiddenByOracleRule() {
    authorization.denyAll();
    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), household.getId())))
        .isInstanceOf(ShareRejections.ProfileNotFound.class);

    authorization.allowAll();
    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), UUID.randomUUID())))
        .isInstanceOf(ShareRejections.HouseholdNotFound.class);
  }

  @Test
  @DisplayName("Should return not-pending when a decision has already won a pending offer")
  void shouldReturnNotPendingWhenDecisionAlreadyWonPendingOffer() {
    var offer = pendingShare();

    assertThat(service.acceptProfileShare(identity(), offer.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(shares.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ACTIVE);

    assertThat(rejectionOf(service.rejectProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotPending.class);
    assertThat(rejectionOf(service.cancelProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotPending.class);
  }

  @Test
  @DisplayName("Should authorize inside the mutation transaction when an offer is accepted")
  void shouldAuthorizeInsideMutationTransactionWhenOfferIsAccepted() {
    var offer = pendingShare();
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should authorize inside the mutation transaction when a share is offered")
  void shouldAuthorizeInsideMutationTransactionWhenShareIsOffered() {
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), household.getId())))
        .isInstanceOf(ShareRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should authorize inside the mutation transaction when an offer is rejected")
  void shouldAuthorizeInsideMutationTransactionWhenOfferIsRejected() {
    var offer = pendingShare();
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(rejectionOf(service.rejectProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should authorize inside the mutation transaction when an offer is canceled")
  void shouldAuthorizeInsideMutationTransactionWhenOfferIsCanceled() {
    var offer = pendingShare();
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(rejectionOf(service.cancelProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should authorize inside the mutation transaction when a share is ended")
  void shouldAuthorizeInsideMutationTransactionWhenShareIsEnded() {
    var active = activeShare();
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(rejectionOf(service.endProfileShare(identity(), active.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName(
      "Should authorize inside the mutation transaction when a share is administratively ended")
  void shouldAuthorizeInsideMutationTransactionWhenShareIsAdministrativelyEnded() {
    var active = activeShare();
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(
            rejectionOf(
                service.administrativelyEndProfileShare(
                    identity(), active.getId(), "abuse report")))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should return not-pending when an expired offer is accepted")
  void shouldReturnNotPendingWhenExpiredOfferIsAccepted() {
    var offer = pendingShare();
    offer.setExpiresAt(NOW.minusSeconds(1));

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotPending.class);
  }

  @Test
  @DisplayName("Should set canceled status when an offerer cancels a pending offer")
  void shouldSetCanceledStatusWhenOffererCancelsPendingOffer() {
    var offer = pendingShare();

    var canceled = service.cancelProfileShare(identity(), offer.getId());

    assertThat(canceled).isInstanceOf(Outcome.Accepted.class);
    assertThat(shares.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.CANCELED);
  }

  @Test
  @DisplayName("Should clear selections and drop the visitor home when a share ends")
  void shouldClearSelectionsAndDropVisitorHomeWhenShareEnds() {
    var visitor =
        accounts.save(
            AccountFixture.defaultAccountBuilder().personalProfileId(profile.getId()).build());
    var active = activeShare();
    var watching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(household.getId())
                .selectedProfileId(profile.getId())
                .deviceName("tv")
                .build());
    var visiting =
        sessions.save(
            AuthSession.builder()
                .accountId(visitor.getId())
                .contextHouseholdId(household.getId())
                .deviceName("web")
                .build());

    var ended = service.endProfileShare(identity(), active.getId());

    assertThat(ended).isInstanceOf(Outcome.Accepted.class);
    assertThat(shares.findById(active.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
    assertThat(sessions.findById(watching.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(sessions.findById(visiting.getId()).orElseThrow().getContextHouseholdId()).isNull();
  }

  @Test
  @DisplayName("Should return not-active when a pending share is ended")
  void shouldReturnNotActiveWhenPendingShareIsEnded() {
    var offer = pendingShare();

    assertThat(rejectionOf(service.endProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotActive.class);
  }

  @Test
  @DisplayName(
      "Should require a reason after authorization and audit when the administrative end succeeds")
  void shouldRequireReasonAfterAuthorizationAndAuditWhenAdministrativelyEndSucceeds() {
    var active = activeShare();

    assertThat(
            rejectionOf(service.administrativelyEndProfileShare(identity(), active.getId(), " ")))
        .isInstanceOf(ShareRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents())
        .containsExactly(new Intent.AdministrativelyEndProfileShare(active.getId()));

    var ended = service.administrativelyEndProfileShare(identity(), active.getId(), "abuse report");
    assertThat(ended).isInstanceOf(Outcome.Accepted.class);

    assertThat(
            rejectionOf(
                service.administrativelyEndProfileShare(
                    identity(), active.getId(), "duplicate attempt")))
        .isInstanceOf(ShareRejections.ShareNotActive.class);
    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("administrativelyEndProfileShare");
  }

  @Test
  @DisplayName("Should require reauthentication when the administrative-end ceremony is stale")
  void shouldRequireReauthenticationWhenAdministrativelyEndCeremonyIsStale() {
    var active = activeShare();
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyEndProfileShare
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    assertThat(
            rejectionOf(
                service.administrativelyEndProfileShare(identity(), active.getId(), "abuse")))
        .isInstanceOf(ShareRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should fail closed when an ordinary end is denied for missing reauthentication")
  void shouldFailClosedWhenOrdinaryEndIsDeniedForMissingReauthentication() {
    var active = activeShare();
    var identity = identity();
    var shareId = active.getId();
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.EndProfileShare
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    assertThatThrownBy(() -> service.endProfileShare(identity, shareId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should answer the membership refusal when a viewer ends a structural share")
  void shouldAnswerMembershipRefusalWhenViewerEndsStructuralShare() {
    var structural = shares.share(profile.getId(), household.getId(), true);
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.EndProfileShare
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : allowed());

    assertThat(rejectionOf(service.endProfileShare(identity(), structural.getId())))
        .isInstanceOf(ShareRejections.StructuralShareCannotEnd.class);
    assertThat(shares.findById(structural.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should distinguish not-found from forbidden when visibility differs")
  void shouldDistinguishNotFoundFromForbiddenWhenVisibilityDiffers() {
    var active = activeShare();
    var identity = identity();
    var shareId = active.getId();

    authorization.denyAll();
    assertThat(rejectionOf(service.endProfileShare(identity, shareId)))
        .isInstanceOf(ShareRejections.ShareNotFound.class);

    authorization.decideUnitWith(
        intent -> intent instanceof Intent.EndProfileShare ? denied() : allowed());
    assertThatThrownBy(() -> service.endProfileShare(identity, shareId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when pending-offer authorization is unavailable")
  void shouldFailClosedWhenPendingOfferAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var householdId = household.getId();
    var options = paginationOptions();

    assertThatThrownBy(() -> service.pendingShareOffers(identity, householdId, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Profile-share authorization is unavailable")
  void shouldFailClosedWhenProfileShareAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var profileId = profile.getId();
    var options = paginationOptions();

    assertThatThrownBy(() -> service.profileShares(identity, profileId, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should return an empty page when pending offers are not visible")
  void shouldReturnEmptyPageWhenPendingOffersAreNotVisible() {
    pendingShare();
    authorization.denyAll();

    var page = service.pendingShareOffers(identity(), household.getId(), paginationOptions());

    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("Should explain a withdrawn offer when the offerer lost authority before acceptance")
  void shouldExplainWithdrawnOfferWhenOffererLostAuthorityBeforeAcceptance() {
    var offer = pendingShare();
    authorization.decideForAccountWith(_ -> new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isEqualTo(new ShareRejections.OfferInvalidated("offerer no longer authorized"));
    assertThat(shares.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should explain a withdrawn offer when it was invalidated before acceptance")
  void shouldExplainWithdrawnOfferWhenItWasInvalidatedBeforeAcceptance() {
    var offer = pendingShare();
    shares.tryInvalidatePending(offer.getId(), "issuer disabled", NOW);

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isEqualTo(new ShareRejections.OfferInvalidated("issuer disabled"));
  }

  @Test
  @DisplayName("Should omit an expired offer when pending offers are listed")
  void shouldOmitExpiredOfferWhenPendingOffersAreListed() {
    shares.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.PENDING)
            .expiresAt(NOW.minusSeconds(1))
            .build());

    var page = service.pendingShareOffers(identity(), household.getId(), paginationOptions());

    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("Should return an empty page when Profile shares are not visible")
  void shouldReturnEmptyPageWhenProfileSharesAreNotVisible() {
    activeShare();
    authorization.denyAll();

    var page = service.profileShares(identity(), profile.getId(), paginationOptions());

    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("Should expose only wouldLock and nameConflict when an offer is preflighted")
  void shouldExposeOnlyWouldLockAndNameConflictWhenOfferIsPreflighted() {
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
    shares.save(activeShareBuilder().profileId(kid.getId()).householdId(household.getId()).build());
    var twin =
        profiles.save(ProfileFixture.defaultProfileBuilder().name(profile.getName()).build());
    shares.save(
        activeShareBuilder().profileId(twin.getId()).householdId(household.getId()).build());

    var preflight =
        service.sharePreflight(identity(), profile.getId(), household.getId()).orElseThrow();

    // A Kid is available there and this Adult has no PIN: it would lock; the name collides.
    assertThat(preflight.wouldLock()).isTrue();
    assertThat(preflight.nameConflict()).isTrue();

    authorization.denyAll();
    assertThat(service.sharePreflight(identity(), profile.getId(), household.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should ignore the Profile itself when its name conflict is checked")
  void shouldIgnoreProfileItselfWhenNameConflictIsChecked() {
    activeShare();

    var preflight =
        service.sharePreflight(identity(), profile.getId(), household.getId()).orElseThrow();

    assertThat(preflight.nameConflict()).isFalse();
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private ProfileHouseholdShare pendingShare() {
    return shares.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(identity().accountId())
            .expiresAt(NOW.plusSeconds(3600))
            .build());
  }

  private ProfileHouseholdShare activeShare() {
    return shares.save(
        activeShareBuilder().profileId(profile.getId()).householdId(household.getId()).build());
  }

  @Test
  @DisplayName("Should return not-found when an allowed principal offers a missing Profile")
  void shouldReturnNotFoundWhenAllowedPrincipalOffersMissingProfile() {
    authorization.allowAll();
    assertThat(
            rejectionOf(
                service.offerProfileShare(identity(), UUID.randomUUID(), household.getId())))
        .isInstanceOf(ShareRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName(
      "Should answer an empty preview when an allowed principal previews a missing Profile")
  void shouldAnswerEmptyPreviewWhenAllowedPrincipalPreviewsMissingProfile() {
    authorization.allowAll();
    assertThat(service.sharePreflight(identity(), UUID.randomUUID(), household.getId())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should answer an empty page when an allowed principal lists shares of a missing Profile")
  void shouldAnswerEmptyPageWhenAllowedPrincipalListsSharesOfMissingProfile() {
    authorization.allowAll();

    var page = service.profileShares(identity(), UUID.randomUUID(), paginationOptions());

    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should answer ShareNotFound when an unauthorized non-viewer administratively ends with a blank reason")
  void shouldAnswerShareNotFoundWhenUnauthorizedNonViewerAdministrativelyEndsWithBlankReason() {
    var active = activeShare();
    authorization.denyAll();

    assertThat(
            rejectionOf(service.administrativelyEndProfileShare(identity(), active.getId(), "  ")))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  // ---- A missing share answers ShareNotFound for every verb, whatever the policy arm.

  @Test
  @DisplayName("Should return ShareNotFound when an allowed principal accepts a missing share")
  void shouldReturnShareNotFoundWhenAllowedPrincipalAcceptsMissingShare() {
    assertThat(rejectionOf(service.acceptProfileShare(identity(), UUID.randomUUID())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should return ShareNotFound when an allowed principal rejects a missing share")
  void shouldReturnShareNotFoundWhenAllowedPrincipalRejectsMissingShare() {
    assertThat(rejectionOf(service.rejectProfileShare(identity(), UUID.randomUUID())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName("Should return ShareNotFound when an allowed principal cancels a missing share")
  void shouldReturnShareNotFoundWhenAllowedPrincipalCancelsMissingShare() {
    assertThat(rejectionOf(service.cancelProfileShare(identity(), UUID.randomUUID())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  private static Decision<AuthorizationUnit> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<AuthorizationUnit> denied() {
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }

  private static Decision<AuthorizationUnit> denyInsideMutationTransaction(
      Intent.UnitIntent ignoredIntent) {
    return TransactionSynchronizationManager.isActualTransactionActive() ? denied() : allowed();
  }

  private static KeysetPaginationOptions paginationOptions() {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(100)
            .build());
  }
}
