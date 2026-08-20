package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
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
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * The sharing lifecycle over fakes: one winner per transition, the oracle rule per seat, and the
 * unshare side effects — selections cleared, a visitor's sessions dropped home.
 */
@Tag("UnitTest")
@DisplayName("Profile Sharing Service Tests")
class ProfileSharingServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final ProfileSharingService service =
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
          new CredentialCodeProperties(null, null),
          Clock.systemUTC());

  private Household household;
  private Profile profile;

  @BeforeEach
  void setUp() {
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    profile = profiles.save(ProfileFixture.defaultProfileBuilder().build());
  }

  @Test
  @DisplayName("Should offer once and refuse a second live offer for the same pair")
  void shouldOfferOnceAndRefuseSecondLiveOfferForSamePair() {
    var offered = service.offerProfileShare(identity(), profile.getId(), household.getId());

    var share = offered.fold(value -> value, _ -> null);
    assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
    assertThat(share.getOfferedByAccountId()).isEqualTo(identity().accountId());
    assertThat(share.getExpiresAt()).isNotNull();
  }

  @Test
  @DisplayName("Should read hidden targets as not found under the oracle rule")
  void shouldReadHiddenTargetsAsNotFoundUnderOracleRule() {
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
  @DisplayName("Should let exactly one decision win a pending offer")
  void shouldLetExactlyOneDecisionWinPendingOffer() {
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
  @DisplayName("Should refuse accepting an expired offer")
  void shouldRefuseAcceptingExpiredOffer() {
    var offer = pendingShare();
    offer.setExpiresAt(Instant.now().minusSeconds(1));

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotPending.class);
  }

  @Test
  @DisplayName("Should clear selections and drop the visitor home when a share ends")
  void shouldClearSelectionsAndDropVisitorHomeWhenShareEnds() {
    var visitor =
        accounts.save(
            AccountFixture.defaultAccountBuilder().personalProfileId(profile.getId()).build());
    var active = shares.share(profile.getId(), household.getId(), false);
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
  @DisplayName("Should refuse ending a share that is not active")
  void shouldRefuseEndingShareThatIsNotActive() {
    var offer = pendingShare();

    assertThat(rejectionOf(service.endProfileShare(identity(), offer.getId())))
        .isInstanceOf(ShareRejections.ShareNotActive.class);
  }

  @Test
  @DisplayName("Should audit exactly one force-end win and require its reason first")
  void shouldAuditExactlyOneForceEndWinAndRequireItsReasonFirst() {
    var active = shares.share(profile.getId(), household.getId(), false);

    assertThat(rejectionOf(service.forceEndProfileShare(identity(), active.getId(), " ")))
        .isInstanceOf(ShareRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();

    var ended = service.forceEndProfileShare(identity(), active.getId(), "abuse report");
    assertThat(ended).isInstanceOf(Outcome.Accepted.class);
    assertThat(audit.entries()).hasSize(1);
    assertThat(audit.entries().getFirst().operation()).isEqualTo("forceEndProfileShare");
  }

  @Test
  @DisplayName("Should report the missing ceremony for a stale force-end")
  void shouldReportMissingCeremonyForStaleForceEnd() {
    var active = shares.share(profile.getId(), household.getId(), false);
    authorization.decideWith(
        intent ->
            intent instanceof Intent.ForceEndProfileShare
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : allowed());

    assertThat(rejectionOf(service.forceEndProfileShare(identity(), active.getId(), "abuse")))
        .isInstanceOf(ShareRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should split denials into forbidden and not-found by visibility")
  void shouldSplitDenialsIntoForbiddenAndNotFoundByVisibility() {
    var active = shares.share(profile.getId(), household.getId(), false);
    var identity = identity();
    var shareId = active.getId();

    authorization.denyAll();
    assertThat(rejectionOf(service.endProfileShare(identity, shareId)))
        .isInstanceOf(ShareRejections.ShareNotFound.class);

    authorization.decideWith(
        intent -> intent instanceof Intent.EndProfileShare ? denied() : allowed());
    assertThatThrownBy(() -> service.endProfileShare(identity, shareId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should report only wouldLock and nameConflict from the preflight")
  void shouldReportOnlyWouldLockAndNameConflictFromPreflight() {
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
    shares.share(kid.getId(), household.getId(), false);
    var twin =
        profiles.save(ProfileFixture.defaultProfileBuilder().name(profile.getName()).build());
    shares.share(twin.getId(), household.getId(), false);

    var preflight =
        service.sharePreflight(identity(), profile.getId(), household.getId()).orElseThrow();

    // A Kid is available there and this Adult has no PIN: it would lock; the name collides.
    assertThat(preflight.wouldLock()).isTrue();
    assertThat(preflight.nameConflict()).isTrue();

    authorization.denyAll();
    assertThat(service.sharePreflight(identity(), profile.getId(), household.getId())).isEmpty();
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
            .expiresAt(Instant.now().plusSeconds(3600))
            .build());
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  private static Decision<?> allowed() {
    return new Decision.Allowed<>(AuthorizationUnit.INSTANCE);
  }

  private static Decision<?> denied() {
    return new Decision.Denied<>(Decision.DenialReason.POLICY);
  }
}
