package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.ProfileHouseholdShareFixture.activeShareBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeShareCodeRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.PaginationFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.auth.ShareCodes;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PageRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The sharing lifecycle over fakes: one winner per transition, resource visibility for each
 * operation, and the unshare side effects — selections cleared, a visitor's sessions dropped home.
 */
@Tag("UnitTest")
@DisplayName("Profile Sharing Service Tests")
class ProfileSharingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
  private static final Duration INVITATION_TTL = Duration.ofDays(2);
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository(this::householdOfProfile);
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final FakeShareCodeRepository shareCodes = new FakeShareCodeRepository();
  private final FakeCredentialAttemptRepository attempts = new FakeCredentialAttemptRepository();
  private final ShareCodes codes = new ShareCodes();

  private Household household;
  private Profile profile;
  private ShareCodeService shareCodeService;
  private ProfileSharingService service;

  @BeforeEach
  void setUp() {
    var mutationTransactions =
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator());
    var credentialCodes =
        CredentialCodeProperties.builder()
            .invitationTtl(INVITATION_TTL)
            .passwordResetTtl(Duration.ofHours(1))
            .shareCodeTtl(Duration.ofDays(7))
            .replacementLockTimeout(Duration.ofSeconds(5))
            .build();
    shareCodeService =
        new ShareCodeService(
            authorization,
            shareCodes,
            households,
            accounts,
            mutationTransactions,
            attempts.gate(CLOCK),
            new HouseholdShareCodesList(shareCodes, CLOCK),
            codes,
            credentialCodes,
            CLOCK);
    service =
        new ProfileSharingService(
            authorization,
            shares,
            new ProfileShareLifecycle(shares, new CapturingEventPublisher()),
            profiles,
            households,
            accounts,
            sessions,
            new DeviceRegistrationLifecycle(registrations, sessions),
            audit,
            mutationTransactions,
            credentialCodes,
            new HouseholdSharesList(shares, CLOCK),
            new ProfileSharesList(shares, CLOCK),
            shareCodeService,
            shareCodes,
            CLOCK);
    household = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    profile = profiles.save(ProfileFixture.defaultProfileBuilder().build());
  }

  @Test
  @DisplayName(
      "Should create a pending offer with its offerer and expiry when an offer is authorized")
  void shouldCreatePendingOfferWithOffererAndExpiryWhenOfferIsAuthorized() {
    var offered =
        service.offerProfileShare(identity(), profile.getId(), presenting(shareCodeFor(household)));

    assertThat(offered).isInstanceOf(Outcome.Accepted.class);
    var share = acceptedShare(offered);
    assertThat(share.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
    assertThat(share.getOfferedByAccountId()).isEqualTo(identity().accountId());
    assertThat(share.getExpiresAt()).isEqualTo(NOW.plus(INVITATION_TTL));
  }

  @Test
  @DisplayName("Should offer to the Household the code names and spend the code when it is live")
  void shouldOfferToHouseholdCodeNamesAndSpendCodeWhenItIsLive() {
    var elsewhere = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var code = shareCodeFor(elsewhere);

    var share =
        acceptedShare(service.offerProfileShare(identity(), profile.getId(), presenting(code)));

    assertThat(share.getHouseholdId()).isEqualTo(elsewhere.getId());
    assertThat(storedCode(code).getStatus()).isEqualTo(ShareCodeStatus.SPENT);
    assertThat(storedCode(code).getEndedAt()).isEqualTo(NOW);
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata().kind()).isEqualTo(CredentialKind.SHARE_CODE);
              assertThat(attempt.metadata().accountId()).isEqualTo(identity().accountId());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should refuse a second offer that presents a code already spent")
  void shouldRefuseSecondOfferThatPresentsCodeAlreadySpent() {
    var code = shareCodeFor(household);
    var other = profiles.save(ProfileFixture.defaultProfileBuilder().name("Other").build());
    acceptedShare(service.offerProfileShare(identity(), profile.getId(), presenting(code)));

    assertThat(rejectionOf(service.offerProfileShare(identity(), other.getId(), presenting(code))))
        .isEqualTo(new ShareCodeRejections.ShareCodeRefused());
    assertThat(shares.findAll())
        .extracting(ProfileHouseholdShare::getProfileId)
        .containsExactly(profile.getId());
    assertThat(attempts.attempts())
        .extracting(FakeCredentialAttemptRepository.AttemptSnapshot::result)
        .containsExactly(CredentialAttemptResult.SUCCEEDED, CredentialAttemptResult.FAILED);
  }

  @Test
  @DisplayName("Should refuse the offer and leave the code spent when it is spent after presenting")
  void shouldRefuseOfferAndLeaveCodeSpentWhenItIsSpentAfterPresenting() {
    var code = shareCodeFor(household);
    // Another offer spends the code after this one presented it and before it writes.
    authorization.decideUnitWith(
        intent -> {
          if (intent instanceof Intent.OfferProfileShare) {
            shareCodes.trySpend(storedCode(code).getId(), NOW);
          }

          return allowed();
        });

    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), presenting(code))))
        .isEqualTo(new ShareCodeRejections.ShareCodeRefused());
    assertThat(shares.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the offer as not found and leave the code live when it is hidden")
  void shouldRefuseOfferAsNotFoundAndLeaveCodeLiveWhenItIsHidden() {
    var code = shareCodeFor(household);
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewShareCodeHousehold ? allowed() : denied());

    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), presenting(code))))
        .isInstanceOf(ShareRejections.ProfileNotFound.class);
    assertThat(storedCode(code).statusAt(NOW)).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(shares.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse in one way and offer nothing when the code was never issued")
  void shouldRefuseInOneWayAndOfferNothingWhenCodeWasNeverIssued() {
    assertThat(
            rejectionOf(
                service.offerProfileShare(identity(), profile.getId(), presenting(unissuedCode()))))
        .isEqualTo(new ShareCodeRejections.ShareCodeRefused());
    assertThat(shares.findAll()).isEmpty();
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED));
  }

  @Test
  @DisplayName("Should refuse an offer before counting an attempt when the session may not")
  void shouldRefuseOfferBeforeCountingAttemptWhenSessionMayNot() {
    var code = shareCodeFor(household);
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewShareCodeHousehold ? denied() : allowed());

    assertThatThrownBy(
            () -> service.offerProfileShare(identity(), profile.getId(), presenting(code)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(attempts.attempts()).isEmpty();
    assertThat(storedCode(code).statusAt(NOW)).isEqualTo(ShareCodeStatus.LIVE);
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
    var code = shareCodeFor(household);
    authorization.decideUnitWith(ProfileSharingServiceTest::denyInsideMutationTransaction);

    assertThat(
            rejectionOf(service.offerProfileShare(identity(), profile.getId(), presenting(code))))
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
  @DisplayName(
      "Should answer not-found when a reader who only administers the target Household decides a"
          + " share that is over")
  void shouldAnswerNotFoundWhenReaderWhoOnlyAdministersTargetHouseholdDecidesShareThatIsOver() {
    var declined = shareOver(ProfileShareStatus.REJECTED);
    var lapsed = pendingShare();
    lapsed.setExpiresAt(NOW.minusSeconds(1));
    var ended = shareOver(ProfileShareStatus.ENDED);
    authorization.decideUnitWith(intent -> readsSharesThatAreOver(intent) ? denied() : allowed());

    assertThat(rejectionOf(service.rejectProfileShare(identity(), declined.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
    assertThat(rejectionOf(service.acceptProfileShare(identity(), lapsed.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
    assertThat(rejectionOf(service.endProfileShare(identity(), ended.getId())))
        .isInstanceOf(ShareRejections.ShareNotFound.class);
  }

  @Test
  @DisplayName(
      "Should answer not-pending when the Profile's side or a reader of the Household's share"
          + " history decides a share that is over")
  void shouldAnswerNotPendingWhenProfileSideOrShareHistoryReaderDecidesShareThatIsOver() {
    var declined = shareOver(ProfileShareStatus.REJECTED);

    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewHouseholdShareHistory ? denied() : allowed());
    var forProfileSide = rejectionOf(service.cancelProfileShare(identity(), declined.getId()));
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewProfileShareHouseholds ? denied() : allowed());
    var forHistoryReader = rejectionOf(service.rejectProfileShare(identity(), declined.getId()));

    assertThat(forProfileSide).isInstanceOf(ShareRejections.ShareNotPending.class);
    assertThat(forHistoryReader).isInstanceOf(ShareRejections.ShareNotPending.class);
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
  @DisplayName(
      "Should require reauthentication when ending a share administratively with stale credentials")
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
  @DisplayName(
      "Should answer not-found to every verb when the reader only hosts the Profile of a share into"
          + " another Household")
  void shouldAnswerNotFoundToEveryVerbWhenReaderOnlyHostsProfileOfShareIntoAnotherHousehold() {
    var offered = pendingShare();
    var active = activeShare();
    var home = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var structural = shares.share(profile.getId(), home.getId(), true);
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewProfileAdministration(profile.getId()))
                ? allowed()
                : denied());
    var reader = identity();

    assertThat(
            List.of(
                answerOf(() -> service.acceptProfileShare(reader, offered.getId())),
                answerOf(() -> service.rejectProfileShare(reader, offered.getId())),
                answerOf(() -> service.cancelProfileShare(reader, offered.getId())),
                answerOf(() -> service.endProfileShare(reader, active.getId())),
                answerOf(() -> service.endProfileShare(reader, structural.getId())),
                answerOf(
                    () ->
                        service.administrativelyEndProfileShare(
                            reader, structural.getId(), "closing"))))
        .containsOnly("ShareNotFound");
  }

  @Test
  @DisplayName(
      "Should answer not-found to every verb when the caller may make the change but may not read"
          + " the share")
  void shouldAnswerNotFoundToEveryVerbWhenCallerMayMakeChangeButMayNotReadShare() {
    var offered = pendingShare();
    var active = activeShare();
    authorization.decideUnitWith(intent -> readsShare(intent) ? denied() : allowed());
    var caller = identity();

    assertThat(
            List.of(
                answerOf(() -> service.acceptProfileShare(caller, active.getId())),
                answerOf(() -> service.rejectProfileShare(caller, active.getId())),
                answerOf(() -> service.cancelProfileShare(caller, active.getId())),
                answerOf(() -> service.endProfileShare(caller, offered.getId())),
                answerOf(
                    () ->
                        service.administrativelyEndProfileShare(
                            caller, offered.getId(), "closing"))))
        .containsOnly("ShareNotFound");
  }

  @Test
  @DisplayName("Should cancel the offer when its offerer may no longer read it and it is pending")
  void shouldCancelOfferWhenItsOffererMayNoLongerReadItAndItIsPending() {
    var offer = pendingShare();
    authorization.decideUnitWith(intent -> readsShare(intent) ? denied() : allowed());

    var canceled = service.cancelProfileShare(identity(), offer.getId());

    assertThat(canceled).isInstanceOf(Outcome.Accepted.class);
    assertThat(shares.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.CANCELED);
  }

  @Test
  @DisplayName("Should fail closed when Household-share authorization is unavailable")
  void shouldFailClosedWhenHouseholdShareAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var scope = everyShareInto(household);
    var options = paginationOptions();

    assertThatThrownBy(() -> service.householdShares(identity, scope, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Profile-share authorization is unavailable")
  void shouldFailClosedWhenProfileShareAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var scope = everyShareOf(profile.getId());
    var options = paginationOptions();

    assertThatThrownBy(() -> service.profileShares(identity, scope, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should return an empty page when a Household's shares are not visible")
  void shouldReturnEmptyPageWhenHouseholdSharesAreNotVisible() {
    pendingShare();
    authorization.denyAll();

    var page = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(page.rows()).isEmpty();
  }

  @Test
  @DisplayName("Should ask for the Household's administration when its shares are listed")
  void shouldAskForHouseholdAdministrationWhenItsSharesAreListed() {
    var offer = pendingShare();
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewHouseholdAdministration(household.getId()))
                ? allowed()
                : denied());

    var page = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(page.rows()).extracting(row -> row.item().getId()).containsExactly(offer.getId());
  }

  @Test
  @DisplayName(
      "Should list only the offers still pending and the active shares when the Household's"
          + " HouseholdAdmin lists every status")
  void shouldListOnlyPendingOffersAndActiveSharesWhenHouseholdAdminListsEveryStatus() {
    var live = sharesOfEveryOutcomeInto(household);
    administerHouseholdOnly();

    var page = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(page.rows())
        .extracting(row -> row.item().getId())
        .containsExactlyInAnyOrderElementsOf(live);
  }

  @Test
  @DisplayName(
      "Should keep only the live statuses it names when the Household's HouseholdAdmin names some"
          + " that are over")
  void shouldKeepOnlyLiveStatusesItNamesWhenHouseholdAdminNamesSomeThatAreOver() {
    var offer = pendingShare();
    sharesOfEveryOutcomeInto(household);
    administerHouseholdOnly();

    var pendingOrDeclined =
        service.householdShares(
            identity(),
            sharesInto(household, ProfileShareStatus.PENDING, ProfileShareStatus.REJECTED),
            paginationOptions());
    var over =
        service.householdShares(
            identity(),
            sharesInto(
                household,
                ProfileShareStatus.REJECTED,
                ProfileShareStatus.CANCELED,
                ProfileShareStatus.EXPIRED,
                ProfileShareStatus.INVALIDATED,
                ProfileShareStatus.ENDED),
            paginationOptions());

    assertThat(pendingOrDeclined.rows())
        .extracting(row -> row.item().getId())
        .contains(offer.getId())
        .allSatisfy(
            id ->
                assertThat(shares.findById(id).orElseThrow().statusAt(NOW))
                    .isEqualTo(ProfileShareStatus.PENDING));
    assertThat(over.rows()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should list every share into the Household at every status when the reader may view its"
          + " share history")
  void shouldListEveryShareIntoHouseholdAtEveryStatusWhenReaderMayViewItsShareHistory() {
    sharesOfEveryOutcomeInto(household);
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewHouseholdAdministration(household.getId()))
                    || intent.equals(new Intent.ViewHouseholdShareHistory(household.getId()))
                ? allowed()
                : denied());

    var page = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(page.rows())
        .extracting(row -> row.item().statusAt(NOW))
        .containsExactlyInAnyOrder(ProfileShareStatus.values());
  }

  @Test
  @DisplayName("Should list every share into the Household when no status is named")
  void shouldListEveryShareIntoHouseholdWhenNoStatusIsNamed() {
    var offer = pendingShare();
    var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    var visit =
        shares.save(
            activeShareBuilder().profileId(other.getId()).householdId(household.getId()).build());

    var page = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(page.rows())
        .extracting(row -> row.item().getId())
        .containsExactlyInAnyOrder(offer.getId(), visit.getId());
  }

  @Test
  @DisplayName("Should keep only the named statuses when a Household's shares are listed")
  void shouldKeepOnlyNamedStatusesWhenHouseholdSharesAreListed() {
    var offer = pendingShare();
    var other = profiles.save(ProfileFixture.defaultProfileBuilder().build());
    shares.save(
        activeShareBuilder().profileId(other.getId()).householdId(household.getId()).build());

    var page =
        service.householdShares(
            identity(), sharesInto(household, ProfileShareStatus.PENDING), paginationOptions());

    assertThat(page.rows()).extracting(row -> row.item().getId()).containsExactly(offer.getId());
  }

  @Test
  @DisplayName("Should keep only visitors' shares when a Household's shares are kept to visitors")
  void shouldKeepOnlyVisitorsSharesWhenHouseholdsSharesAreKeptToVisitors() {
    var elsewhere = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var visitor =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(elsewhere.getId()).build());
    var own =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
    var visit =
        shares.save(
            activeShareBuilder().profileId(visitor.getId()).householdId(household.getId()).build());
    var home =
        shares.save(
            activeShareBuilder().profileId(own.getId()).householdId(household.getId()).build());

    var visitors =
        service.householdShares(
            identity(),
            new HouseholdSharesScope(new ShareListScope(household.getId(), List.of()), true),
            paginationOptions());
    var every = service.householdShares(identity(), everyShareInto(household), paginationOptions());

    assertThat(visitors.rows())
        .extracting(row -> row.item().getId())
        .containsExactly(visit.getId());
    assertThat(every.rows())
        .extracting(row -> row.item().getId())
        .containsExactlyInAnyOrder(visit.getId(), home.getId());
  }

  @Test
  @DisplayName(
      "Should answer that the offer was withdrawn when the offerer lost authority before acceptance")
  void shouldAnswerThatOfferWasWithdrawnWhenOffererLostAuthorityBeforeAcceptance() {
    var offer = pendingShare();
    authorization.decideForAccountWith(_ -> new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isEqualTo(new ShareRejections.OfferInvalidated());
    var withdrawn = shares.findById(offer.getId()).orElseThrow();
    assertThat(withdrawn.getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(withdrawn.getInvalidationReason()).contains("offerer no longer authorized");
  }

  @Test
  @DisplayName(
      "Should answer that the offer was withdrawn when it was invalidated before acceptance")
  void shouldAnswerThatOfferWasWithdrawnWhenItWasInvalidatedBeforeAcceptance() {
    var offer = pendingShare();
    shares.tryInvalidatePending(offer.getId(), "issuer disabled", NOW);

    assertThat(rejectionOf(service.acceptProfileShare(identity(), offer.getId())))
        .isEqualTo(new ShareRejections.OfferInvalidated());
  }

  @Test
  @DisplayName(
      "Should list an offer past its expiry as expired when a Household's shares are listed")
  void shouldListOfferPastItsExpiryAsExpiredWhenHouseholdSharesAreListed() {
    var stale =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(household.getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(NOW.minusSeconds(1))
                .build());

    var pending =
        service.householdShares(
            identity(), sharesInto(household, ProfileShareStatus.PENDING), paginationOptions());
    var expired =
        service.householdShares(
            identity(), sharesInto(household, ProfileShareStatus.EXPIRED), paginationOptions());

    assertThat(pending.rows()).isEmpty();
    assertThat(expired.rows()).extracting(row -> row.item().getId()).containsExactly(stale.getId());
  }

  @Test
  @DisplayName("Should return an empty page when Profile shares are not visible")
  void shouldReturnEmptyPageWhenProfileSharesAreNotVisible() {
    activeShare();
    authorization.denyAll();

    var page =
        service.profileShares(identity(), everyShareOf(profile.getId()), paginationOptions());

    assertThat(page.rows()).isEmpty();
  }

  @Test
  @DisplayName("Should return an empty page when the reader only hosts the Profile")
  void shouldReturnEmptyPageWhenReaderOnlyHostsProfile() {
    activeShare();
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewProfileAdministration(profile.getId()))
                ? allowed()
                : denied());

    var page =
        service.profileShares(identity(), everyShareOf(profile.getId()), paginationOptions());

    assertThat(page.rows()).isEmpty();
  }

  @Test
  @DisplayName("Should keep only the named statuses when a Profile's shares are listed")
  void shouldKeepOnlyNamedStatusesWhenProfileSharesAreListed() {
    var visit = activeShare();
    var offer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(profile.getId())
                .householdId(
                    households.save(HouseholdFixture.defaultHouseholdBuilder().build()).getId())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(NOW.plusSeconds(3600))
                .build());
    shares.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(UUID.randomUUID())
            .status(ProfileShareStatus.REJECTED)
            .build());

    var live =
        service.profileShares(
            identity(),
            new ShareListScope(
                profile.getId(), List.of(ProfileShareStatus.ACTIVE, ProfileShareStatus.PENDING)),
            paginationOptions());
    var every =
        service.profileShares(identity(), everyShareOf(profile.getId()), paginationOptions());

    assertThat(live.rows())
        .extracting(row -> row.item().getId())
        .containsExactlyInAnyOrder(visit.getId(), offer.getId());
    assertThat(every.rows()).hasSize(3);
  }

  @Test
  @DisplayName(
      "Should place each share where the Household's list holds it when a HouseholdAdmin reads"
          + " shares of every outcome")
  void shouldPlaceEachShareWhereTheHouseholdsListHoldsItWhenAHouseholdAdminReadsEveryOutcome() {
    var live = sharesOfEveryOutcomeInto(household);
    administerHouseholdOnly();

    var listed =
        service.householdShares(identity(), everyShareInto(household), paginationOptions()).rows();
    var placed =
        shares.findAll().stream()
            .flatMap(
                share ->
                    service
                        .householdShareRow(identity(), everyShareInto(household), share.getId())
                        .stream())
            .toList();

    assertThat(listed)
        .extracting(row -> row.item().getId())
        .containsExactlyInAnyOrderElementsOf(live);
    assertThat(placed).containsExactlyInAnyOrderElementsOf(listed);
  }

  @Test
  @DisplayName(
      "Should place a share that is over when the reader may read the Household's share history")
  void shouldPlaceAShareThatIsOverWhenTheReaderMayReadTheHouseholdsShareHistory() {
    var declined = declinedShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewHouseholdAdministration(var id) when id.equals(household.getId()) ->
                  allowed();
              case Intent.ViewHouseholdShareHistory(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });

    var placed = service.householdShareRow(identity(), everyShareInto(household), declined.getId());
    var listed =
        service.householdShares(identity(), everyShareInto(household), paginationOptions()).rows();

    assertThat(listed).hasSize(1);
    assertThat(placed).contains(listed.getFirst());
  }

  @Test
  @DisplayName("Should place no share when the statuses the reader asks for leave it out")
  void shouldPlaceNoShareWhenTheStatusesTheReaderAsksForLeaveItOut() {
    var offer = pendingShare();

    var active =
        service.householdShareRow(
            identity(), sharesInto(household, ProfileShareStatus.ACTIVE), offer.getId());
    var pending =
        service.householdShareRow(
            identity(), sharesInto(household, ProfileShareStatus.PENDING), offer.getId());

    assertThat(active).isEmpty();
    assertThat(pending).map(row -> row.item().getId()).contains(offer.getId());
  }

  @Test
  @DisplayName("Should place no share when the reader may not read the Household's shares")
  void shouldPlaceNoShareWhenTheReaderMayNotReadTheHouseholdsShares() {
    var offer = pendingShare();
    authorization.denyAll();

    var placed = service.householdShareRow(identity(), everyShareInto(household), offer.getId());

    assertThat(placed).isEmpty();
  }

  @Test
  @DisplayName(
      "Should place a share where the Profile's list holds it when the reader may read its shares")
  void shouldPlaceAShareWhereTheProfilesListHoldsItWhenTheReaderMayReadItsShares() {
    declinedShare();
    var visit = activeShare();

    var placed = service.profileShareRow(identity(), everyShareOf(profile.getId()), visit.getId());
    var listed =
        service
            .profileShares(identity(), everyShareOf(profile.getId()), paginationOptions())
            .rows();

    assertThat(listed).hasSize(2);
    assertThat(placed).isPresent();
    assertThat(listed).contains(placed.orElseThrow());
  }

  @Test
  @DisplayName(
      "Should place no share of a Profile when the statuses the reader asks for leave it out")
  void shouldPlaceNoShareOfAProfileWhenTheStatusesTheReaderAsksForLeaveItOut() {
    var visit = activeShare();

    var declinedOnly =
        service.profileShareRow(
            identity(),
            new ShareListScope(profile.getId(), List.of(ProfileShareStatus.REJECTED)),
            visit.getId());
    var activeOnly =
        service.profileShareRow(
            identity(),
            new ShareListScope(profile.getId(), List.of(ProfileShareStatus.ACTIVE)),
            visit.getId());

    assertThat(declinedOnly).isEmpty();
    assertThat(activeOnly).map(row -> row.item().getId()).contains(visit.getId());
  }

  @Test
  @DisplayName("Should place no share of a Profile when the reader only hosts the Profile")
  void shouldPlaceNoShareOfAProfileWhenTheReaderOnlyHostsTheProfile() {
    var visit = activeShare();
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewProfileAdministration(profile.getId()))
                ? allowed()
                : denied());

    var placed = service.profileShareRow(identity(), everyShareOf(profile.getId()), visit.getId());

    assertThat(placed).isEmpty();
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

    var code = shareCodeFor(household);

    var preflight =
        service.sharePreflight(identity(), profile.getId(), presenting(code)).orElseThrow();

    // A Kid is available there and this Adult has no PIN: it would lock; the name collides.
    assertThat(preflight.wouldLock()).isTrue();
    assertThat(preflight.nameConflict()).isTrue();

    authorization.decideUnitWith(
        intent -> intent instanceof Intent.OfferProfileShare ? denied() : allowed());
    assertThat(service.sharePreflight(identity(), profile.getId(), presenting(code))).isEmpty();
  }

  @Test
  @DisplayName("Should answer for the Household its share code names when two codes are presented")
  void shouldAnswerForHouseholdItsShareCodeNamesWhenTwoCodesArePresented() {
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().build());
    shares.save(activeShareBuilder().profileId(kid.getId()).householdId(household.getId()).build());
    var quiet = households.save(HouseholdFixture.defaultHouseholdBuilder().build());

    var intoKidsHousehold =
        service
            .sharePreflight(identity(), profile.getId(), presenting(shareCodeFor(household)))
            .orElseThrow();
    var intoQuietHousehold =
        service
            .sharePreflight(identity(), profile.getId(), presenting(shareCodeFor(quiet)))
            .orElseThrow();

    assertThat(intoKidsHousehold.wouldLock()).isTrue();
    assertThat(intoQuietHousehold.wouldLock()).isFalse();
  }

  @Test
  @DisplayName("Should ignore the Profile itself when its name conflict is checked")
  void shouldIgnoreProfileItselfWhenNameConflictIsChecked() {
    activeShare();

    var preflight =
        service
            .sharePreflight(identity(), profile.getId(), presenting(shareCodeFor(household)))
            .orElseThrow();

    assertThat(preflight.nameConflict()).isFalse();
  }

  @Test
  @DisplayName("Should keep the share code live and count one attempt when an offer is preflighted")
  void shouldKeepShareCodeLiveAndCountOneAttemptWhenOfferIsPreflighted() {
    var code = shareCodeFor(household);

    service.sharePreflight(identity(), profile.getId(), presenting(code)).orElseThrow();

    assertThat(storedCode(code).statusAt(NOW)).isEqualTo(ShareCodeStatus.LIVE);
    assertThat(attempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata().kind()).isEqualTo(CredentialKind.SHARE_CODE);
              assertThat(attempt.metadata().accountId()).isEqualTo(identity().accountId());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
  }

  @Test
  @DisplayName("Should answer nothing and count a failure when the preflight's share code fails")
  void shouldAnswerNothingAndCountFailureWhenPreflightsShareCodeFails() {
    var spent = shareCodeFor(household);
    shareCodes.trySpend(storedCode(spent).getId(), NOW);

    assertThat(service.sharePreflight(identity(), profile.getId(), presenting(spent))).isEmpty();
    assertThat(service.sharePreflight(identity(), profile.getId(), presenting(unissuedCode())))
        .isEmpty();
    assertThat(attempts.attempts())
        .hasSize(2)
        .allSatisfy(
            attempt -> assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED));
  }

  @Test
  @DisplayName("Should refuse a preflight before counting an attempt when the session may not")
  void shouldRefusePreflightBeforeCountingAttemptWhenSessionMayNot() {
    var code = shareCodeFor(household);
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewShareCodeHousehold ? denied() : allowed());

    assertThatThrownBy(() -> service.sharePreflight(identity(), profile.getId(), presenting(code)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(attempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should give the share as it stands when the reader administers its Household")
  void shouldGiveTheShareAsItStandsWhenTheReaderAdministersItsHousehold() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdAdministration(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });

    var change = service.shareChange(identity(), changeOf(share)).orElseThrow();

    assertThat(change.shareId()).isEqualTo(share.getId());
    assertThat(change.share()).containsSame(share);
  }

  @Test
  @DisplayName(
      "Should give the change that ends a share and none after it when the reader administers its"
          + " Household")
  void shouldGiveChangeThatEndsShareAndNoneAfterItWhenReaderAdministersItsHousehold() {
    var share = declinedShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdAdministration(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });

    var ending = service.shareChange(identity(), changeOf(share));
    var after = service.shareChange(identity(), laterChangeOf(share));

    assertThat(ending).hasValueSatisfying(change -> assertThat(change.share()).contains(share));
    assertThat(after).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give the change that ended a share as it was made when the share is gone at its"
          + " delivery to the Household's HouseholdAdmin")
  void shouldGiveChangeThatEndedShareAsItWasMadeWhenShareIsGoneAtDeliveryToItsHouseholdAdmin() {
    var share = pendingShare();
    share.setStatus(ProfileShareStatus.INVALIDATED);
    share.setInvalidationReason("profile deleted");
    var withdrawal = changeOf(share);
    shares.deleteById(share.getId());
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdAdministration(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });

    var delivered = service.shareChange(identity(), withdrawal).orElseThrow().share();

    assertThat(delivered)
        .hasValueSatisfying(
            ended -> {
              assertThat(ended.getId()).isEqualTo(share.getId());
              assertThat(ended.getProfileId()).isEqualTo(profile.getId());
              assertThat(ended.getHouseholdId()).isEqualTo(household.getId());
              assertThat(ended.getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
              assertThat(ended.getOfferedByAccountId()).isNull();
              assertThat(ended.getInvalidationReason()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should give nothing to the Household's HouseholdAdmin when a change that waited is delivered"
          + " after its share ended and was deleted")
  void shouldGiveNothingToHouseholdAdminWhenWaitingChangeArrivesAfterShareWasDeleted() {
    var share = activeShare();
    var accepted = changeOf(share);
    share.setStatus(ProfileShareStatus.ENDED);
    shares.deleteById(share.getId());
    followChangesAndAdministerHouseholdOnly();

    assertThat(service.shareChange(identity(), accepted)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give nothing to the Household's HouseholdAdmin when a change that waited is delivered"
          + " after its share ended")
  void shouldGiveNothingToHouseholdAdminWhenWaitingChangeArrivesAfterShareEnded() {
    var share = pendingShare();
    var offered = changeOf(share);
    share.setStatus(ProfileShareStatus.CANCELED);
    followChangesAndAdministerHouseholdOnly();

    assertThat(service.shareChange(identity(), offered)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give the removal to the Household's HouseholdAdmin when its share is deleted while"
          + " live")
  void shouldGiveRemovalToHouseholdAdminWhenItsShareIsDeletedWhileLive() {
    var share = activeShare();
    var removal = removalOf(share);
    shares.deleteById(share.getId());
    followChangesAndAdministerHouseholdOnly();

    assertThat(service.shareChange(identity(), removal))
        .hasValueSatisfying(change -> assertThat(change.share()).isEmpty());
  }

  @Test
  @DisplayName(
      "Should give nothing to the Household's HouseholdAdmin when a live share's removal is"
          + " delivered after its Household was deleted")
  void shouldGiveNothingToHouseholdAdminWhenRemovalArrivesAfterItsHouseholdWasDeleted() {
    var share = activeShare();
    var removal = removalOf(share);
    shares.deleteById(share.getId());
    households.deleteById(household.getId());
    followChangesAndAdministerHouseholdOnly();

    assertThat(service.shareChange(identity(), removal)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give the removal to a reader of the Household's share history when it is delivered"
          + " after the Household was deleted")
  void shouldGiveRemovalToShareHistoryReaderWhenItArrivesAfterTheHouseholdWasDeleted() {
    var share = activeShare();
    var removal = removalOf(share);
    shares.deleteById(share.getId());
    households.deleteById(household.getId());
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdAdministration _ -> allowed();
              case Intent.ViewHouseholdShareHistory _ -> allowed();
              default -> denied();
            });

    assertThat(service.shareChange(identity(), removal))
        .hasValueSatisfying(change -> assertThat(change.share()).isEmpty());
  }

  @Test
  @DisplayName(
      "Should give the share as it stands with the change that ends it when the reader also reads"
          + " it from the Profile's side or the Household's share history")
  void shouldGiveShareAsItStandsWithChangeThatEndsItWhenReaderAlsoReadsItFromProfileOrHistory() {
    var share = pendingShare();
    share.setStatus(ProfileShareStatus.INVALIDATED);
    share.setInvalidationReason("offerer no longer authorized");
    var withdrawal = changeOf(share);
    var reasons = new ArrayList<Optional<String>>();

    for (var alsoReads :
        List.of(
            new Intent.ViewProfileShareHouseholds(profile.getId()),
            new Intent.ViewHouseholdShareHistory(household.getId()))) {
      authorization.decideUnitWith(
          intent ->
              intent instanceof Intent.ViewProfileShareChanges
                      || intent.equals(new Intent.ViewHouseholdAdministration(household.getId()))
                      || intent.equals(alsoReads)
                  ? allowed()
                  : denied());
      reasons.add(
          service
              .shareChange(identity(), withdrawal)
              .flatMap(ShareChange::share)
              .flatMap(ProfileHouseholdShare::getInvalidationReason));
    }

    assertThat(reasons)
        .containsExactly(
            Optional.of("offerer no longer authorized"),
            Optional.of("offerer no longer authorized"));
  }

  @Test
  @DisplayName(
      "Should give every change of a share that is over when the reader may view the Household's"
          + " share history")
  void shouldGiveEveryChangeOfShareThatIsOverWhenReaderMayViewHouseholdsShareHistory() {
    var share = declinedShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdShareHistory(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });

    assertThat(service.shareChange(identity(), laterChangeOf(share)))
        .hasValueSatisfying(change -> assertThat(change.share()).contains(share));
  }

  @Test
  @DisplayName(
      "Should give every change of a share that is over when the reader is on the offering side")
  void shouldGiveEveryChangeOfShareThatIsOverWhenReaderIsOnOfferingSide() {
    var share = declinedShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewProfileShareHouseholds(var id) when id.equals(profile.getId()) ->
                  allowed();
              default -> denied();
            });

    assertThat(service.shareChange(identity(), laterChangeOf(share)))
        .hasValueSatisfying(change -> assertThat(change.share()).contains(share));
  }

  @Test
  @DisplayName("Should give the share when the reader is on the Profile's offering side")
  void shouldGiveTheShareWhenTheReaderIsOnTheProfilesOfferingSide() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewProfileShareHouseholds(var id) when id.equals(profile.getId()) ->
                  allowed();
              default -> denied();
            });

    assertThat(service.shareChange(identity(), changeOf(share)))
        .hasValueSatisfying(change -> assertThat(change.share()).contains(share));
  }

  @Test
  @DisplayName("Should give nothing when the reader only hosts the Profile in another Household")
  void shouldGiveNothingWhenTheReaderOnlyHostsTheProfileInAnotherHousehold() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewProfileAdministration(var id) when id.equals(profile.getId()) ->
                  allowed();
              default -> denied();
            });

    assertThat(service.shareChange(identity(), changeOf(share))).isEmpty();
  }

  @Test
  @DisplayName("Should give nothing when the reader may read neither side of the share")
  void shouldGiveNothingWhenTheReaderMayReadNeitherSideOfTheShare() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewProfileShareChanges ? allowed() : denied());

    assertThat(service.shareChange(identity(), changeOf(share))).isEmpty();
  }

  @Test
  @DisplayName("Should decide a change by its Household alone when its Profile no longer exists")
  void shouldDecideAChangeByItsHouseholdAloneWhenItsProfileNoLongerExists() {
    var removed =
        new ProfileShareChangedEvent(
            new ChangedShare(
                UUID.randomUUID(), UUID.randomUUID(), household.getId(), ProfileShareStatus.ACTIVE),
            true);
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewProfileAdministration _, Intent.ViewProfileShareHouseholds _ ->
                  new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE);
              default -> denied();
            });

    assertThat(service.shareChange(identity(), removed)).isEmpty();

    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewProfileAdministration
                    || intent instanceof Intent.ViewProfileShareHouseholds
                ? new Decision.Failed<>(Decision.FailureCause.INVALID_SLICE)
                : allowed());
    assertThat(service.shareChange(identity(), removed))
        .hasValueSatisfying(change -> assertThat(change.share()).isEmpty());
  }

  @Test
  @DisplayName("Should give the change without a share when the share no longer exists")
  void shouldGiveTheChangeWithoutAShareWhenTheShareNoLongerExists() {
    var share = pendingShare();
    shares.deleteById(share.getId());

    var change = service.shareChange(identity(), changeOf(share)).orElseThrow();

    assertThat(change.shareId()).isEqualTo(share.getId());
    assertThat(change.share()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the change when the reader may no longer follow share changes")
  void shouldRefuseTheChangeWhenTheReaderMayNoLongerFollowShareChanges() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent -> intent instanceof Intent.ViewProfileShareChanges ? denied() : allowed());
    var reader = identity();
    var changed = changeOf(share);

    assertThatThrownBy(() -> service.shareChange(reader, changed))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.requireViewProfileShareChanges(reader))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when the reader's authority over the share cannot be decided")
  void shouldFailClosedWhenTheReadersAuthorityOverTheShareCannotBeDecided() {
    var share = pendingShare();
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewProfileShareChanges
                ? allowed()
                : new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));
    var reader = identity();
    var changed = changeOf(share);

    assertThatThrownBy(() -> service.shareChange(reader, changed))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  /** A change of a share that was pending or active until it, or still is. */
  private static ProfileShareChangedEvent changeOf(ProfileHouseholdShare share) {
    return new ProfileShareChangedEvent(ChangedShare.of(share), true);
  }

  /** A change of a share that was already over, such as its deletion with its Profile. */
  private static ProfileShareChangedEvent laterChangeOf(ProfileHouseholdShare share) {
    return new ProfileShareChangedEvent(ChangedShare.of(share), false);
  }

  /** The deletion of a share that is pending or active, with its Profile or its Household. */
  private static ProfileShareChangedEvent removalOf(ProfileHouseholdShare share) {
    return new ProfileShareChangedEvent(ChangedShare.of(share), true, true);
  }

  private void followChangesAndAdministerHouseholdOnly() {
    authorization.decideUnitWith(
        intent ->
            switch (intent) {
              case Intent.ViewProfileShareChanges _ -> allowed();
              case Intent.ViewHouseholdAdministration(var id) when id.equals(household.getId()) ->
                  allowed();
              default -> denied();
            });
  }

  private ProfileHouseholdShare declinedShare() {
    return shares.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(ProfileShareStatus.REJECTED)
            .offeredByAccountId(identity().accountId())
            .expiresAt(NOW.plusSeconds(3600))
            .build());
  }

  private void administerHouseholdOnly() {
    authorization.decideUnitWith(
        intent ->
            intent.equals(new Intent.ViewHouseholdAdministration(household.getId()))
                ? allowed()
                : denied());
  }

  /**
   * One share of its own Profile into the Household for every outcome, an offer past its expiry
   * among them; answers the ids of the offer still pending and the active share.
   */
  private List<UUID> sharesOfEveryOutcomeInto(Household into) {
    var live = new ArrayList<UUID>();
    for (var status : ProfileShareStatus.values()) {
      var offered = profiles.save(ProfileFixture.defaultProfileBuilder().build());
      var share =
          shares.save(
              ProfileHouseholdShare.builder()
                  .profileId(offered.getId())
                  .householdId(into.getId())
                  .status(
                      status == ProfileShareStatus.EXPIRED ? ProfileShareStatus.PENDING : status)
                  .offeredByAccountId(identity().accountId())
                  .expiresAt(
                      status == ProfileShareStatus.EXPIRED
                          ? NOW.minusSeconds(1)
                          : NOW.plusSeconds(3600))
                  .build());
      if (status.isLive()) {
        live.add(share.getId());
      }
    }

    return live;
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

  private ProfileHouseholdShare shareOver(ProfileShareStatus status) {
    return shares.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(household.getId())
            .status(status)
            .offeredByAccountId(identity().accountId())
            .build());
  }

  // The two authorities that still read a share once it is over (ADR 0024 §Read authorization).
  private static boolean readsSharesThatAreOver(Intent.UnitIntent intent) {
    return intent instanceof Intent.ViewHouseholdShareHistory
        || intent instanceof Intent.ViewProfileShareHouseholds;
  }

  // Every authority that reads a share: those above and its Household's HouseholdAdmins.
  private static boolean readsShare(Intent.UnitIntent intent) {
    return readsSharesThatAreOver(intent) || intent instanceof Intent.ViewHouseholdAdministration;
  }

  @Test
  @DisplayName("Should return not-found when an allowed principal offers a missing Profile")
  void shouldReturnNotFoundWhenAllowedPrincipalOffersMissingProfile() {
    authorization.allowAll();
    var code = shareCodeFor(household);

    assertThat(
            rejectionOf(service.offerProfileShare(identity(), UUID.randomUUID(), presenting(code))))
        .isInstanceOf(ShareRejections.ProfileNotFound.class);
    assertThat(storedCode(code).statusAt(NOW)).isEqualTo(ShareCodeStatus.LIVE);
  }

  @Test
  @DisplayName(
      "Should answer an empty preview when an allowed principal previews a missing Profile")
  void shouldAnswerEmptyPreviewWhenAllowedPrincipalPreviewsMissingProfile() {
    authorization.allowAll();
    assertThat(
            service.sharePreflight(
                identity(), UUID.randomUUID(), presenting(shareCodeFor(household))))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "Should answer an empty page when an allowed principal lists shares of a missing Profile")
  void shouldAnswerEmptyPageWhenAllowedPrincipalListsSharesOfMissingProfile() {
    authorization.allowAll();

    var page =
        service.profileShares(identity(), everyShareOf(UUID.randomUUID()), paginationOptions());

    assertThat(page.rows()).isEmpty();
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

  /** A live share code for the Household, issued as a HouseholdAdmin issues one. */
  private String shareCodeFor(Household target) {
    return shareCodeService
        .issueShareCode(identity(), target.getId())
        .fold(
            NewShareCode::code,
            rejections -> {
              throw new AssertionError("expected a share code but got " + rejections);
            });
  }

  private static ShareCodeCommand presenting(String code) {
    return ShareCodeCommand.builder().code(code).ipAddress("203.0.113.7").build();
  }

  private ShareCode storedCode(String code) {
    return shareCodes.findByCodeDigest(codes.digestOf(code).orElseThrow()).orElseThrow();
  }

  /** A code in the right form that no Household was given. */
  private String unissuedCode() {
    return codes.issue().code();
  }

  private static ProfileHouseholdShare acceptedShare(
      Outcome<ProfileHouseholdShare, ShareRejections.Offer> offered) {
    return offered.fold(
        share -> share,
        rejections -> {
          throw new AssertionError("expected an accepted offer but got " + rejections);
        });
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  /** What a verb answers: the name of its rejection, or FORBIDDEN when it is refused outright. */
  private static String answerOf(Supplier<Outcome<?, ?>> verb) {
    try {
      return rejectionOf(verb.get()).getClass().getSimpleName();
    } catch (AccessDeniedException _) {
      return "FORBIDDEN";
    }
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

  private static PageRequest paginationOptions() {
    return PaginationFixture.firstRows(100);
  }

  private Optional<UUID> householdOfProfile(UUID profileId) {
    return profiles.findById(profileId).map(Profile::getHouseholdId);
  }

  private static HouseholdSharesScope everyShareInto(Household household) {
    return sharesInto(household);
  }

  private static HouseholdSharesScope sharesInto(
      Household household, ProfileShareStatus... statuses) {
    return new HouseholdSharesScope(
        new ShareListScope(household.getId(), List.of(statuses)), false);
  }

  private static ShareListScope everyShareOf(UUID profileId) {
    return new ShareListScope(profileId, List.of());
  }
}
