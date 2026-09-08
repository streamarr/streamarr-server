package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Household deletion over fakes: every other Account must already be gone, the explicit action
 * disposes of the final Account when one remains, and nothing — visit, registration, credential, or
 * resident Profile — outlives the Household.
 */
@Tag("UnitTest")
@DisplayName("Household Deletion Service Tests")
class HouseholdDeletionServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final RefusingProfileRepository profiles = new RefusingProfileRepository(shares);
  private final RefusingAccountRepository accounts = new RefusingAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
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

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t\n"})
  @DisplayName("Should preserve the Household when the deletion reason is missing")
  void shouldPreserveHouseholdWhenDeletionReasonIsMissing(String reason) {
    assertThat(deleteEmptyHousehold(reason))
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.ReasonRequired()));
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(DeletionAction.class)
  @DisplayName("Should preserve all residents when more than one Account remains")
  void shouldPreserveAllResidentsWhenMoreThanOneAccountRemains(DeletionAction action) {
    var first = residentOf(doomed, HouseholdRole.ADMIN);
    var second = residentOf(doomed, HouseholdRole.MEMBER);
    assertThat(attempt(action))
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.AccountsRemain()));
    assertThat(accounts.findByHouseholdId(doomed.getId()))
        .extracting(UserAccount::getId)
        .containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = DeletionAction.class, names = "EMPTY", mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should reject a final-Account disposition when its source has no Account")
  void shouldRejectFinalAccountDispositionWhenSourceHasNoAccount(DeletionAction action) {
    assertThat(attempt(action))
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound()));
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should fail closed when the deletion decision is unavailable")
  void shouldFailClosedWhenDeletionDecisionIsUnavailable() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.DeleteHousehold
                ? new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThatThrownBy(() -> deleteEmptyHousehold("closing"))
        .isInstanceOf(AuthorizationUnavailableException.class);
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should return no preview when an authorized Household does not exist")
  void shouldReturnNoPreviewWhenAuthorizedHouseholdDoesNotExist() {
    assertThat(service.deletionPreflight(identity(), UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should allow a corrected disposition when the first attempt leaves an Account behind")
  void shouldAllowCorrectedDispositionWhenFirstAttemptLeavesAccountBehind() {
    residentOf(doomed, HouseholdRole.ADMIN);
    assertThat(deleteEmptyHousehold("closing"))
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.AccountsRemain()));
    assertThat(audit.entries()).isEmpty();

    assertThat(deleteLastAccountAndHousehold("closing"))
        .isEqualTo(Outcome.accepted(doomed.getId()));
    assertThat(households.findById(doomed.getId())).isEmpty();
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
  @DisplayName("Should return a receipt and record the actor when deleting an empty Household")
  void shouldReturnReceiptAndRecordActorWhenDeletingEmptyHousehold() {
    assertThat(deleteEmptyHousehold("closing shop")).isEqualTo(Outcome.accepted(doomed.getId()));

    assertThat(households.findById(doomed.getId())).isEmpty();
    assertThat(households.findById(refuge.getId())).isPresent();
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
        service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity(),
            preservationCommand().replacementManagerAccountId(UUID.randomUUID()).build());

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
        service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity(),
            preservationCommand().replacementManagerAccountId(outsideManager.getId()).build());

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
        service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity(), preservationCommand().build());

    assertThat(rejectionOf(outcome))
        .isEqualTo(new HouseholdDeletionRejections.ReplacementManagerNotEligible());
  }

  @Test
  @DisplayName("Should reject Household deletion when the final Account cannot transfer")
  void shouldRejectHouseholdDeletionWhenFinalAccountCannotTransfer() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    accounts.refuseTransfer();

    var outcome = transferLastAccountAndDeleteHousehold("closing", refuge.getId());

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound()));
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(profiles.findById(lastResident.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should reject Household deletion when the final Account cannot be deleted")
  void shouldRejectHouseholdDeletionWhenFinalAccountCannotBeDeleted() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    accounts.refuseDeletion();

    var outcome =
        service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity(), preservationCommand().build());

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound()));
    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(profiles.findById(lastResident.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should abort Household deletion when the transferred Personal Profile cannot move")
  void shouldAbortHouseholdDeletionWhenTransferredPersonalProfileCannotMove() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    profiles.refuseRehome();

    assertThatThrownBy(() -> transferLastAccountAndDeleteHousehold("closing", refuge.getId()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("The Personal Profile could not move to the requested Household.");

    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(profiles.findById(lastResident.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should abort Household deletion when the preserved Personal Profile cannot move")
  void shouldAbortHouseholdDeletionWhenPreservedPersonalProfileCannotMove() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    profiles.refuseRehome();

    assertThatThrownBy(
            () ->
                service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                    identity(), preservationCommand().build()))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("The Personal Profile could not move to the requested Household.");

    assertThat(households.findById(doomed.getId())).isPresent();
    assertThat(profiles.findById(lastResident.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should preserve the final Account's Profile behind the destination anchor when requested")
  void shouldPreserveFinalAccountProfileBehindDestinationAnchorWhenRequested() {
    var lastResident = residentOf(doomed, HouseholdRole.ADMIN);
    var outcome =
        service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
            identity(), preservationCommand().build());

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
    var otherProfileActivity = progress.save(progressFor(UUID.randomUUID()));
    AuditFieldSetter.setLastModifiedOn(older, Instant.parse("2026-08-01T00:00:00Z"));
    AuditFieldSetter.setLastModifiedOn(newer, Instant.parse("2026-08-02T00:00:00Z"));
    AuditFieldSetter.setLastModifiedOn(otherProfileActivity, Instant.parse("2026-08-03T00:00:00Z"));

    assertThat(service.profileActivity(identity(), profileId, paginationOptions()).items())
        .extracting(item -> item.item().getId())
        .containsExactly(newer.getId(), older.getId());
  }

  @Test
  @DisplayName("Should continue only the requested Profile's activity when its cursor advances")
  void shouldContinueOnlyRequestedProfileActivityWhenCursorAdvances() {
    var profileId = UUID.randomUUID();
    var older = progress.save(progressFor(profileId));
    var newer = progress.save(progressFor(profileId));
    var foreign = progress.save(progressFor(UUID.randomUUID()));
    AuditFieldSetter.setLastModifiedOn(older, NOW);
    AuditFieldSetter.setLastModifiedOn(newer, NOW.plusSeconds(1));
    AuditFieldSetter.setLastModifiedOn(foreign, NOW.plusSeconds(2));
    var oneItem =
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(1)
            .build();

    var first =
        service.profileActivity(identity(), profileId, new KeysetPaginationOptions(null, oneItem));
    assertThat(first.items())
        .extracting(item -> item.item().getId())
        .containsExactly(newer.getId());
    assertThat(first.hasNextPage()).isTrue();
    assertThat(first.hasPreviousPage()).isFalse();

    var second =
        service.profileActivity(
            identity(), profileId, new KeysetPaginationOptions(newer.getId(), oneItem));
    assertThat(second.items())
        .extracting(item -> item.item().getId())
        .containsExactly(older.getId());
    assertThat(second.hasNextPage()).isFalse();
    assertThat(second.hasPreviousPage()).isTrue();

    var terminal =
        service.profileActivity(
            identity(), profileId, new KeysetPaginationOptions(older.getId(), oneItem));
    assertThat(terminal.items()).isEmpty();
    assertThat(terminal.hasNextPage()).isFalse();
    assertThat(terminal.hasPreviousPage()).isTrue();
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
    var request =
        HouseholdDeletionService.SecurityAuditPageRequest.builder()
            .direction(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    assertThatThrownBy(() -> service.securityAuditEvents(identity, request))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should fail closed when deletion preflight authorization is unavailable")
  void shouldFailClosedWhenDeletionPreflightAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var householdId = doomed.getId();

    assertThatThrownBy(() -> service.deletionPreflight(identity, householdId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should fail closed when Profile activity authorization is unavailable")
  void shouldFailClosedWhenProfileActivityAuthorizationIsUnavailable() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var identity = identity();
    var profileId = UUID.randomUUID();
    var options = paginationOptions();

    assertThatThrownBy(() -> service.profileActivity(identity, profileId, options))
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
    var identity = identity();
    var request =
        HouseholdDeletionService.SecurityAuditPageRequest.builder()
            .direction(PaginationDirection.FORWARD)
            .limit(10)
            .build();

    assertThatThrownBy(() -> service.securityAuditEvents(identity, request))
        .isInstanceOf(AuthorizationUnavailableException.class);
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

  private DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand
          .DeleteLastAccountAndHouseholdPreservingPersonalProfileCommandBuilder
      preservationCommand() {
    return DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
        .householdId(doomed.getId())
        .destinationHouseholdId(refuge.getId())
        .replacementManagerAccountId(refugeAnchor.getId())
        .reason("closing");
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete> attempt(DeletionAction action) {
    return switch (action) {
      case EMPTY -> deleteEmptyHousehold("closing");
      case TRANSFER -> transferLastAccountAndDeleteHousehold("closing", refuge.getId());
      case DELETE -> deleteLastAccountAndHousehold("closing");
      case PRESERVE ->
          service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
              identity(), preservationCommand().build());
    };
  }

  private enum DeletionAction {
    EMPTY,
    TRANSFER,
    DELETE,
    PRESERVE
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
        Clock.fixed(NOW, ZoneOffset.UTC));
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

  private static final class RefusingAccountRepository extends FakeUserAccountRepository {

    private boolean refuseTransfer;
    private boolean refuseDeletion;

    private RefusingAccountRepository(FakeProfileHouseholdShareRepository shares) {
      super(shares);
    }

    void refuseTransfer() {
      refuseTransfer = true;
    }

    void refuseDeletion() {
      refuseDeletion = true;
    }

    @Override
    public boolean tryDelete(UUID accountId, UUID expectedHouseholdId) {
      return !refuseDeletion && super.tryDelete(accountId, expectedHouseholdId);
    }

    @Override
    public boolean tryTransfer(
        UUID accountId, UUID expectedHouseholdId, UUID destinationHouseholdId, HouseholdRole role) {
      return !refuseTransfer
          && super.tryTransfer(accountId, expectedHouseholdId, destinationHouseholdId, role);
    }
  }

  private static final class RefusingProfileRepository extends FakeProfileRepository {

    private boolean refuseRehome;

    private RefusingProfileRepository(FakeProfileHouseholdShareRepository shares) {
      super(shares);
    }

    void refuseRehome() {
      refuseRehome = true;
    }

    @Override
    public boolean tryRehome(
        UUID profileId, UUID expectedHouseholdId, UUID destinationHouseholdId) {
      return !refuseRehome
          && super.tryRehome(profileId, expectedHouseholdId, destinationHouseholdId);
    }
  }
}
