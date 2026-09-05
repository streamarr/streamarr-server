package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeDeviceRegistrationRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakePasswordResetCodeRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteEmptyHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Household deletion over fakes: every other Account must already be gone, the explicit action
 * disposes of the final Account when one remains, and nothing — visit, registration, credential, or
 * resident Profile — outlives the Household.
 */
@Tag("UnitTest")
@DisplayName("Household Deletion Service Tests")
class HouseholdDeletionServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final PausingHouseholdRepository households = new PausingHouseholdRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository managerInvitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeAccountInvitationRepository accountInvitations =
      new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository passwordResetCodes =
      new FakePasswordResetCodeRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeSessionProgressRepository progress = new FakeSessionProgressRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final HouseholdDeletionService service = serviceUsing(accounts);

  private Household doomed;
  private Household refuge;
  private UserAccount refugeAnchor;

  @BeforeEach
  void setUp() {
    doomed = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    refuge = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    refugeAnchor = residentOf(refuge, HouseholdRole.ADMIN);
  }

  @Test
  @DisplayName("Should reject without authorizing when the deletion reason is blank")
  void shouldRejectWithoutAuthorizingWhenDeletionReasonIsBlank() {
    assertThat(rejectionOf(deleteEmptyHousehold(" ")))
        .isInstanceOf(HouseholdDeletionRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should require reauthentication when deletion authorization requests a fresh ceremony")
  void shouldRequireReauthenticationWhenDeletionAuthorizationRequestsFreshCeremony() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.DeleteHousehold
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(rejectionOf(deleteEmptyHousehold("dispute")))
        .isInstanceOf(HouseholdDeletionRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should reject empty-Household deletion when multiple Accounts remain")
  void shouldRejectEmptyHouseholdDeletionWhenMultipleAccountsRemain() {
    residentOf(doomed, HouseholdRole.ADMIN);
    residentOf(doomed, HouseholdRole.MEMBER);
    assertThat(rejectionOf(deleteEmptyHousehold("closing")))
        .isInstanceOf(HouseholdDeletionRejections.AccountsRemain.class);
  }

  @Test
  @DisplayName("Should reject empty-Household deletion when one Account remains")
  void shouldRejectEmptyHouseholdDeletionWhenOneAccountRemains() {
    var single = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    residentOf(single, HouseholdRole.ADMIN);
    assertThat(
            rejectionOf(
                service.deleteEmptyHousehold(
                    identity(),
                    DeleteEmptyHouseholdCommand.builder()
                        .householdId(single.getId())
                        .reason("closing")
                        .build())))
        .isInstanceOf(HouseholdDeletionRejections.AccountsRemain.class);
  }

  @Test
  @DisplayName("Should reject final-Account deletion when the Household is empty")
  void shouldRejectFinalAccountDeletionWhenHouseholdIsEmpty() {
    assertThat(
            rejectionOf(
                service.deleteLastAccountAndHousehold(
                    identity(),
                    DeleteLastAccountAndHouseholdCommand.builder()
                        .householdId(doomed.getId())
                        .reason("closing")
                        .build())))
        .isInstanceOf(HouseholdDeletionRejections.LastAccountNotFound.class);
  }

  @Test
  @DisplayName("Should report Household not found when the authorized Household does not exist")
  void shouldReportHouseholdNotFoundWhenAuthorizedHouseholdDoesNotExist() {
    var missing = UUID.randomUUID();

    var outcome =
        service.deleteEmptyHousehold(
            identity(),
            DeleteEmptyHouseholdCommand.builder().householdId(missing).reason("closing").build());

    assertThat(rejectionOf(outcome)).isEqualTo(new HouseholdDeletionRejections.HouseholdNotFound());
  }

  @Test
  @DisplayName("Should throw access denied when policy denies a visible Household deletion")
  void shouldThrowAccessDeniedWhenPolicyDeniesVisibleHouseholdDeletion() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewHouseholdAdministration
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));

    assertThatThrownBy(() -> deleteEmptyHousehold("closing"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should hide Household existence when policy denies an invisible Household deletion")
  void shouldHideHouseholdExistenceWhenPolicyDeniesInvisibleHouseholdDeletion() {
    authorization.denyAll();

    assertThat(rejectionOf(deleteEmptyHousehold("closing")))
        .isEqualTo(new HouseholdDeletionRejections.HouseholdNotFound());
  }

  @Test
  @DisplayName("Should reject the source Household when transfer names it as the destination")
  void shouldRejectSourceHouseholdWhenTransferNamesItAsDestination() {
    residentOf(doomed, HouseholdRole.ADMIN);

    var outcome = transferLastAccountAndDeleteHousehold("closing", doomed.getId());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.DestinationNotFound());
  }

  @Test
  @DisplayName("Should reject a missing Household when transfer names it as the destination")
  void shouldRejectMissingHouseholdWhenTransferNamesItAsDestination() {
    residentOf(doomed, HouseholdRole.ADMIN);

    var outcome = transferLastAccountAndDeleteHousehold("closing", UUID.randomUUID());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.DestinationNotFound());
  }

  @Test
  @DisplayName("Should leave nothing behind when an empty Household is deleted")
  void shouldLeaveNothingBehindWhenEmptyHouseholdIsDeleted() {
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(doomed.getId()).build());
    shares.share(orphan.getId(), doomed.getId(), false);
    var visit = shares.share(refugeAnchor.getPersonalProfileId(), doomed.getId(), false);
    var invitation =
        accountInvitations.save(
            AccountInvitation.builder()
                .recipientEmail("late@example.com")
                .householdId(doomed.getId())
                .householdName("Doomed")
                .householdRole(HouseholdRole.MEMBER)
                .profileName("Late")
                .profileKind(ProfileKind.ADULT)
                .issuerAccountId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("pub-household-deletion")
                .secretDigest(new byte[] {1})
                .build());
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("esn-doomed")
                .displayName("TV")
                .householdId(doomed.getId())
                .authorizingAccountId(refugeAnchor.getId())
                .build());

    var outcome = deleteEmptyHousehold("closing shop");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(households.findById(doomed.getId())).isEmpty();
    assertThat(profiles.findById(orphan.getId())).isEmpty();
    assertThat(shares.findById(visit.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ENDED);
    assertThat(accountInvitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("deleteEmptyHousehold")
                .actorAccountId(identity().accountId())
                .reason("closing shop")
                .resource("householdId", doomed.getId())
                .build());
  }

  @Test
  @DisplayName(
      "Should revoke remote registrations and their sessions when the authorizing Account is deleted")
  void shouldRevokeRemoteRegistrationsAndSessionsWhenAuthorizingAccountIsDeleted() {
    var finalAccount = residentOf(doomed, HouseholdRole.ADMIN);
    var remoteRegistration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("remote-esn")
                .displayName("Remote TV")
                .householdId(refuge.getId())
                .authorizingAccountId(finalAccount.getId())
                .build());
    var deviceSession =
        sessions.save(
            AuthSession.builder()
                .accountId(refugeAnchor.getId())
                .deviceName("Remote TV")
                .registrationId(remoteRegistration.getId())
                .contextHouseholdId(refuge.getId())
                .build());

    var outcome = deleteLastAccountAndHousehold("closing");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(registrations.findById(remoteRegistration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(deviceSession.getId()).orElseThrow().getRevokedReason())
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
  }

  @Test
  @DisplayName(
      "Should reset visitor context and revoke visited-Household devices when the Household is deleted")
  void shouldResetVisitorContextAndRevokeVisitedHouseholdDevicesWhenHouseholdIsDeleted() {
    shares.share(refugeAnchor.getPersonalProfileId(), doomed.getId(), false);
    var browserSession =
        sessions.save(
            AuthSession.builder()
                .accountId(refugeAnchor.getId())
                .deviceName("Browser")
                .contextHouseholdId(doomed.getId())
                .selectedProfileId(refugeAnchor.getPersonalProfileId())
                .build());
    var visitedRegistration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("visited-esn")
                .displayName("Visited TV")
                .householdId(doomed.getId())
                .authorizingAccountId(refugeAnchor.getId())
                .build());
    var deviceSession =
        sessions.save(
            AuthSession.builder()
                .accountId(refugeAnchor.getId())
                .deviceName("Visited TV")
                .registrationId(visitedRegistration.getId())
                .contextHouseholdId(doomed.getId())
                .build());

    var outcome = deleteEmptyHousehold("closing");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var reset = sessions.findById(browserSession.getId()).orElseThrow();
    assertThat(reset.getContextHouseholdId()).isNull();
    assertThat(reset.getSelectedProfileId()).isNull();
    assertThat(registrations.findById(visitedRegistration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(deviceSession.getId()).orElseThrow().getRevokedReason())
        .isEqualTo(SessionRevocationReason.ADMIN_REVOCATION);
  }

  @Test
  @DisplayName("Should invalidate pending Profile artifacts when their Profile is deleted")
  void shouldInvalidatePendingProfileArtifactsWhenProfileIsDeleted() {
    var orphan =
        profiles.save(ProfileFixture.defaultProfileBuilder().householdId(doomed.getId()).build());
    var accountInvitation =
        accountInvitations.save(
            AccountInvitation.builder()
                .recipientEmail("pending@example.com")
                .householdId(refuge.getId())
                .householdName("Refuge")
                .householdRole(HouseholdRole.MEMBER)
                .profileId(orphan.getId())
                .profileName(orphan.getName())
                .profileKind(ProfileKind.ADULT)
                .issuerAccountId(refugeAnchor.getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("pending-account")
                .secretDigest(new byte[] {1})
                .build());
    var managerInvitation =
        managerInvitations.save(
            ProfileManagerInvitation.builder()
                .profileId(orphan.getId())
                .profileName(orphan.getName())
                .inviterAccountId(refugeAnchor.getId())
                .inviterDisplayName("Inviter")
                .recipientAccountId(UUID.randomUUID())
                .recipientEmail("manager@example.com")
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId("pending-manager")
                .secretDigest(new byte[] {2})
                .build());

    var outcome = deleteEmptyHousehold("closing");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accountInvitations.findById(accountInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(managerInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName(
      "Should transfer the final Account with its Personal Profile when transfer-and-delete is requested")
  void shouldTransferFinalAccountWithPersonalProfileWhenTransferAndDeleteIsRequested() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);

    var outcome = transferLastAccountAndDeleteHousehold("closing", refuge.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(households.findById(doomed.getId())).isEmpty();
    var moved = accounts.findById(lastResident.getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(refuge.getId());
    assertThat(profiles.findById(moved.getPersonalProfileId()).orElseThrow().getHouseholdId())
        .isEqualTo(refuge.getId());
  }

  @Test
  @DisplayName("Should reject a missing replacement manager when the final Profile is kept")
  void shouldRejectMissingReplacementManagerWhenFinalProfileIsKept() {
    residentOf(doomed, HouseholdRole.ADMIN);

    var outcome =
        deleteLastAccountAndHouseholdPreservingPersonalProfile(
            "closing", refuge.getId(), UUID.randomUUID());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.ReplacementManagerNotFound());
  }

  @Test
  @DisplayName(
      "Should reject a replacement manager when the Account lives outside the destination Household")
  void shouldRejectReplacementManagerWhenAccountLivesOutsideDestinationHousehold() {
    residentOf(doomed, HouseholdRole.ADMIN);
    var elsewhere = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var outsideManager = residentOf(elsewhere, HouseholdRole.ADMIN);

    var outcome =
        deleteLastAccountAndHouseholdPreservingPersonalProfile(
            "closing", refuge.getId(), outsideManager.getId());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.ReplacementManagerNotEligible());
  }

  @Test
  @DisplayName("Should reject a replacement manager when its Personal Profile is restricted")
  void shouldRejectReplacementManagerWhenPersonalProfileIsRestricted() {
    residentOf(doomed, HouseholdRole.ADMIN);
    var restricted = profiles.findById(refugeAnchor.getPersonalProfileId()).orElseThrow();
    restricted.setMaximumAllowedRatingAge(12);
    profiles.save(restricted);

    var outcome =
        deleteLastAccountAndHouseholdPreservingPersonalProfile(
            "closing", refuge.getId(), refugeAnchor.getId());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.ReplacementManagerNotEligible());
  }

  @Test
  @DisplayName(
      "Should preserve the final Account's Profile behind the destination anchor when requested")
  void shouldPreserveFinalAccountProfileBehindDestinationAnchorWhenRequested() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    var outcome =
        deleteLastAccountAndHouseholdPreservingPersonalProfile(
            "closing", refuge.getId(), refugeAnchor.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(lastResident.getId())).isEmpty();
    var preserved = profiles.findById(lastResident.getPersonalProfileId()).orElseThrow();
    assertThat(preserved.getHouseholdId()).isEqualTo(refuge.getId());
    assertThat(managers.existsByAccountIdAndProfileId(refugeAnchor.getId(), preserved.getId()))
        .isTrue();
    assertThat(households.findById(doomed.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should report deletion impact when the caller may view the Household")
  void shouldReportDeletionImpactWhenCallerMayViewHousehold() {
    residentOf(doomed, HouseholdRole.ADMIN);
    var unlinked =
        profiles.save(
            ProfileFixture.defaultProfileBuilder()
                .householdId(doomed.getId())
                .name("Unlinked")
                .build());
    shares.share(refugeAnchor.getPersonalProfileId(), doomed.getId(), false);

    var preflight = service.deletionPreflight(identity(), doomed.getId()).orElseThrow();

    assertThat(preflight.accountCount()).isEqualTo(1);
    assertThat(preflight.unlinkedProfiles())
        .containsExactly(
            new HouseholdDeletionService.DoomedProfileDetails(unlinked.getId(), "Unlinked"));
    assertThat(preflight.hostedVisitCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should hide deletion preflight when the caller may not view the Household")
  void shouldHideDeletionPreflightWhenCallerMayNotViewHousehold() {
    authorization.denyAll();
    assertThat(service.deletionPreflight(identity(), doomed.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should return audit entries when the caller may view the security audit")
  void shouldReturnAuditEntriesWhenCallerMayViewSecurityAudit() {
    var actorId = identity().accountId();
    audit.append(
        SecurityAuditEntry.builder()
            .operation("somethingAudited")
            .actorAccountId(actorId)
            .reason("because")
            .resource("householdId", doomed.getId())
            .build());
    assertThat(
            service
                .securityAuditEvents(
                    identity(),
                    HouseholdDeletionService.SecurityAuditPageRequest.builder()
                        .direction(PaginationDirection.FORWARD)
                        .limit(10)
                        .build())
                .items())
        .singleElement()
        .satisfies(
            item -> {
              var event = item.item();
              assertThat(event.operation()).isEqualTo("somethingAudited");
              assertThat(event.actorAccountId()).isEqualTo(actorId);
              assertThat(event.reason()).isEqualTo("because");
              assertThat(event.outcome()).isEqualTo("SUCCESS");
              assertThat(event.resources()).contains(doomed.getId().toString());
              assertThat(event.occurredAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("Should return Profile activity when the caller may view the Profile")
  void shouldReturnProfileActivityWhenCallerMayViewProfile() {
    var profileId = UUID.randomUUID();
    var older = progress.save(progressFor(profileId));
    var newer = progress.save(progressFor(profileId));
    AuditFieldSetter.setLastModifiedOn(older, Instant.parse("2026-08-01T00:00:00Z"));
    AuditFieldSetter.setLastModifiedOn(newer, Instant.parse("2026-08-02T00:00:00Z"));

    assertThat(service.profileActivity(identity(), profileId, paginationOptions()).items())
        .extracting(item -> item.item().getId())
        .containsExactly(newer.getId(), older.getId());
  }

  @Test
  @DisplayName("Should hide Profile activity when the caller may not view the Profile")
  void shouldHideProfileActivityWhenCallerMayNotViewProfile() {
    var profileId = UUID.randomUUID();
    progress.save(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .profileId(profileId)
            .mediaFileId(UUID.randomUUID())
            .positionSeconds(60)
            .percentComplete(10.0)
            .durationSeconds(600)
            .build());
    authorization.denyAll();
    assertThat(service.profileActivity(identity(), profileId, paginationOptions()).items())
        .isEmpty();
  }

  @Test
  @DisplayName("Should forbid the security audit when the caller may not view it")
  void shouldForbidSecurityAuditWhenCallerMayNotViewIt() {
    authorization.denyAll();
    var identity = identity();
    assertThatThrownBy(
            () ->
                service.securityAuditEvents(
                    identity,
                    HouseholdDeletionService.SecurityAuditPageRequest.builder()
                        .direction(PaginationDirection.FORWARD)
                        .limit(10)
                        .build()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when deletion preflight authorization is unavailable")
  void shouldFailClosedWhenDeletionPreflightAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThatThrownBy(() -> service.deletionPreflight(identity(), doomed.getId()))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Profile activity authorization is unavailable")
  void shouldFailClosedWhenProfileActivityAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThatThrownBy(
            () -> service.profileActivity(identity(), UUID.randomUUID(), paginationOptions()))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when deletion visibility authorization is unavailable")
  void shouldFailClosedWhenDeletionVisibilityAuthorizationIsUnavailable() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.DeleteHousehold
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE));

    assertThatThrownBy(() -> deleteEmptyHousehold("closing"))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when security-audit authorization is unavailable")
  void shouldFailClosedWhenSecurityAuditAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThatThrownBy(
            () ->
                service.securityAuditEvents(
                    identity(),
                    HouseholdDeletionService.SecurityAuditPageRequest.builder()
                        .direction(PaginationDirection.FORWARD)
                        .limit(10)
                        .build()))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should allow only one action when final-Account deletion requests race")
  void shouldAllowOnlyOneActionWhenFinalAccountDeletionRequestsRace() throws Exception {
    residentOf(doomed, HouseholdRole.ADMIN);
    households.pauseNextTwoLocks();
    var transfer =
        TransferLastAccountAndDeleteHouseholdCommand.builder()
            .householdId(doomed.getId())
            .destinationHouseholdId(refuge.getId())
            .reason("transfer")
            .build();
    var delete =
        DeleteLastAccountAndHouseholdCommand.builder()
            .householdId(doomed.getId())
            .reason("delete")
            .build();

    List<Outcome<UUID, HouseholdDeletionRejections.Delete>> outcomes;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Callable<Outcome<UUID, HouseholdDeletionRejections.Delete>>> calls =
          List.of(
              () -> service.transferLastAccountAndDeleteHousehold(identity(), transfer),
              () -> service.deleteLastAccountAndHousehold(identity(), delete));
      outcomes = executor.invokeAll(calls).stream().map(this::completedOutcome).toList();
    }

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .singleElement()
        .satisfies(
            outcome ->
                assertThat(rejectionOf(outcome))
                    .isEqualTo(new HouseholdDeletionRejections.HouseholdNotFound()));
    assertThat(audit.entries()).hasSize(1);
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete> deleteEmptyHousehold(String reason) {
    return service.deleteEmptyHousehold(
        identity(),
        DeleteEmptyHouseholdCommand.builder().householdId(doomed.getId()).reason(reason).build());
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete> transferLastAccountAndDeleteHousehold(
      String reason, UUID destinationHouseholdId) {
    return service.transferLastAccountAndDeleteHousehold(
        identity(),
        TransferLastAccountAndDeleteHouseholdCommand.builder()
            .householdId(doomed.getId())
            .destinationHouseholdId(destinationHouseholdId)
            .reason(reason)
            .build());
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete> deleteLastAccountAndHousehold(
      String reason) {
    return service.deleteLastAccountAndHousehold(
        identity(),
        DeleteLastAccountAndHouseholdCommand.builder()
            .householdId(doomed.getId())
            .reason(reason)
            .build());
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete>
      deleteLastAccountAndHouseholdPreservingPersonalProfile(
          String reason, UUID destinationHouseholdId, UUID replacementManagerAccountId) {
    return service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
        identity(),
        DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
            .householdId(doomed.getId())
            .destinationHouseholdId(destinationHouseholdId)
            .replacementManagerAccountId(replacementManagerAccountId)
            .reason(reason)
            .build());
  }

  private UserAccount residentOf(Household household, HouseholdRole role) {
    var account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(role)
                .build());
    profiles.save(
        ProfileFixture.defaultProfileBuilder()
            .id(account.getPersonalProfileId())
            .householdId(household.getId())
            .name("Resident " + account.getId())
            .build());
    shares.share(account.getPersonalProfileId(), household.getId(), true);
    return account;
  }

  private SessionProgress progressFor(UUID profileId) {
    return SessionProgress.builder()
        .sessionId(UUID.randomUUID())
        .profileId(profileId)
        .mediaFileId(UUID.randomUUID())
        .positionSeconds(60)
        .percentComplete(10.0)
        .durationSeconds(600)
        .build();
  }

  private HouseholdDeletionService serviceUsing(FakeUserAccountRepository accountRepository) {
    var registrationLifecycle = new DeviceRegistrationLifecycle(registrations, sessions);
    return new HouseholdDeletionService(
        authorization,
        new AccountRemoval(
            accountRepository,
            profiles,
            shares,
            managers,
            managerInvitations,
            accountInvitations,
            passwordResetCodes,
            sessions,
            registrationLifecycle),
        households,
        accountRepository,
        profiles,
        shares,
        sessions,
        registrationLifecycle,
        accountInvitations,
        audit,
        progress,
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator()),
        new PaginationService(),
        Clock.systemUTC());
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

  private Outcome<UUID, HouseholdDeletionRejections.Delete> completedOutcome(
      Future<Outcome<UUID, HouseholdDeletionRejections.Delete>> future) {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while awaiting Household deletion", exception);
    } catch (ExecutionException exception) {
      throw new AssertionError("concurrent Household deletion failed", exception.getCause());
    }
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }

  private static final class PausingHouseholdRepository extends FakeHouseholdRepository {

    private volatile CyclicBarrier lockBarrier;

    void pauseNextTwoLocks() {
      lockBarrier = new CyclicBarrier(2);
    }

    @Override
    public boolean lockById(UUID householdId) {
      var barrier = lockBarrier;
      if (barrier != null) {
        try {
          barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
          throw new AssertionError("deletion did not reach the Household lock", exception);
        }
      }

      return super.lockById(householdId);
    }
  }
}
