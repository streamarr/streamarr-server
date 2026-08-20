package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.PasswordResetCodeStatus;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
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
import com.streamarr.server.services.identity.AccountLifecycleService.AdministrativelyDeleteAccountCommand;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.AccountLifecycleService.SourceHouseholdAccess;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Account transfers and deletion over fakes: the Account and its Personal Profile move together,
 * the old Household reads END or KEEP_AS_VISITOR exactly as chosen, and deletion leaves no session,
 * registration, or pending proposal behind.
 */
@Tag("UnitTest")
@DisplayName("Account Lifecycle Service Tests")
class AccountLifecycleServiceTest {

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeHouseholdRepository households = new FakeHouseholdRepository();
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository managerInvitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeAccountInvitationRepository accountInvitations =
      new FakeAccountInvitationRepository();
  private final FakePasswordResetCodeRepository resetCodes = new FakePasswordResetCodeRepository();
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeDeviceRegistrationRepository registrations =
      new FakeDeviceRegistrationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final AccountLifecycleService service =
      new AccountLifecycleService(
          authorization,
          new AccountRemoval(
              accounts,
              profiles,
              shares,
              managers,
              managerInvitations,
              accountInvitations,
              resetCodes,
              sessions,
              new DeviceRegistrationLifecycle(registrations, sessions)),
          accounts,
          profiles,
          households,
          audit,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private Household source;
  private Household destination;
  private UserAccount mover;

  @BeforeEach
  void setUp() {
    source = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    destination = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    mover = residentOf(source, HouseholdRole.MEMBER);
    residentOf(source, HouseholdRole.ADMIN);
  }

  @Test
  @DisplayName("Should move the Account and Personal Profile when source Household access ends")
  void shouldMoveAccountAndPersonalProfileWhenSourceHouseholdAccessEnds() {
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("esn-1")
                .displayName("TV")
                .householdId(source.getId())
                .authorizingAccountId(mover.getId())
                .build());
    var watching =
        sessions.save(
            AuthSession.builder()
                .accountId(mover.getId())
                .contextHouseholdId(source.getId())
                .selectedProfileId(mover.getPersonalProfileId())
                .deviceName("web")
                .build());
    var moved =
        service.transferAccount(
            identity(),
            TransferAccountCommand.builder()
                .accountId(mover.getId())
                .destinationHouseholdId(destination.getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .reason("support")
                .build());

    assertThat(moved).isInstanceOf(Outcome.Accepted.class);
    var account = accounts.findById(mover.getId()).orElseThrow();
    assertThat(account.getHouseholdId()).isEqualTo(destination.getId());
    // The destination's first Account becomes HouseholdAdmin.
    assertThat(account.getHouseholdRole()).isEqualTo(HouseholdRole.ADMIN);
    assertThat(profiles.findById(mover.getPersonalProfileId()).orElseThrow().getHouseholdId())
        .isEqualTo(destination.getId());
    assertThat(structuralShareIn(destination.getId())).isPresent();
    assertThat(
            shares.findByProfileIdAndStatus(mover.getPersonalProfileId(), ProfileShareStatus.ENDED))
        .hasSize(1);
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessions.findById(watching.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(sessions.findById(watching.getId()).orElseThrow().getContextHouseholdId()).isNull();
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("transferAccount")
                .actorAccountId(identity().accountId())
                .reason("support")
                .resource("accountId", mover.getId())
                .build());
  }

  @Test
  @DisplayName("Should assign HouseholdMember when the destination has Accounts")
  void shouldAssignHouseholdMemberWhenDestinationHasAccounts() {
    residentOf(destination, HouseholdRole.ADMIN);

    var moved =
        service.transferAccount(
            identity(),
            TransferAccountCommand.builder()
                .accountId(mover.getId())
                .destinationHouseholdId(destination.getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .build());

    assertThat(moved).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(mover.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);
  }

  @Test
  @DisplayName("Should keep the old Household visit when source Household access is retained")
  void shouldKeepOldHouseholdVisitWhenSourceHouseholdAccessIsRetained() {
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("esn-keep")
                .displayName("TV")
                .householdId(source.getId())
                .authorizingAccountId(mover.getId())
                .build());
    var watching =
        sessions.save(
            AuthSession.builder()
                .accountId(mover.getId())
                .contextHouseholdId(source.getId())
                .selectedProfileId(mover.getPersonalProfileId())
                .deviceName("web")
                .build());

    var moved =
        service.transferAccount(
            identity(),
            TransferAccountCommand.builder()
                .accountId(mover.getId())
                .destinationHouseholdId(destination.getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                .build());

    assertThat(moved).isInstanceOf(Outcome.Accepted.class);
    var kept =
        shares
            .findByProfileIdAndHouseholdIdAndStatus(
                mover.getPersonalProfileId(), source.getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(kept.isStructural()).isFalse();
    assertThat(structuralShareIn(destination.getId())).isPresent();
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.ACTIVE);
    assertThat(sessions.findById(watching.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(sessions.findById(watching.getId()).orElseThrow().getContextHouseholdId())
        .isEqualTo(source.getId());
  }

  @Test
  @DisplayName("Should reject the transfer when the destination is the current Household")
  void shouldRejectTransferWhenDestinationIsCurrentHousehold() {
    assertThat(
            rejectionOf(
                service.transferAccount(
                    identity(),
                    TransferAccountCommand.builder()
                        .accountId(mover.getId())
                        .destinationHouseholdId(source.getId())
                        .sourceHouseholdAccess(SourceHouseholdAccess.END)
                        .build())))
        .isInstanceOf(TransferRejections.SameHousehold.class);
  }

  @Test
  @DisplayName("Should return HouseholdNotFound when the transfer destination does not exist")
  void shouldReturnHouseholdNotFoundWhenTransferDestinationDoesNotExist() {
    assertThat(
            rejectionOf(
                service.transferAccount(
                    identity(),
                    TransferAccountCommand.builder()
                        .accountId(mover.getId())
                        .destinationHouseholdId(UUID.randomUUID())
                        .sourceHouseholdAccess(SourceHouseholdAccess.END)
                        .build())))
        .isInstanceOf(TransferRejections.HouseholdNotFound.class);
  }

  @Test
  @DisplayName("Should reserve the final Account when transfer would empty the Household")
  void shouldReserveFinalAccountWhenTransferWouldEmptyHousehold() {
    var loner =
        residentOf(
            households.save(HouseholdFixture.defaultHouseholdBuilder().build()),
            HouseholdRole.ADMIN);
    assertThat(
            rejectionOf(
                service.transferAccount(
                    identity(),
                    TransferAccountCommand.builder()
                        .accountId(loner.getId())
                        .destinationHouseholdId(destination.getId())
                        .sourceHouseholdAccess(SourceHouseholdAccess.END)
                        .build())))
        .isInstanceOf(TransferRejections.FinalAccount.class);
  }

  @Test
  @DisplayName("Should hide the Account when transfer is unauthorized")
  void shouldHideAccountWhenTransferIsUnauthorized() {
    authorization.denyAll();
    assertThat(
            rejectionOf(
                service.transferAccount(
                    identity(),
                    TransferAccountCommand.builder()
                        .accountId(mover.getId())
                        .destinationHouseholdId(destination.getId())
                        .sourceHouseholdAccess(SourceHouseholdAccess.END)
                        .build())))
        .isInstanceOf(TransferRejections.AccountNotFound.class);
  }

  @Test
  @DisplayName("Should reject an authorized transfer when the Account does not exist")
  void shouldRejectAuthorizedTransferWhenAccountDoesNotExist() {
    assertThat(
            rejectionOf(
                service.transferAccount(
                    identity(),
                    TransferAccountCommand.builder()
                        .accountId(UUID.randomUUID())
                        .destinationHouseholdId(destination.getId())
                        .sourceHouseholdAccess(SourceHouseholdAccess.END)
                        .build())))
        .isInstanceOf(TransferRejections.AccountNotFound.class);
  }

  @Test
  @DisplayName("Should erase the Account, Profile, and artifacts when Profile cleanup is requested")
  void shouldEraseAccountProfileAndArtifactsWhenProfileCleanupIsRequested() {
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("esn-1")
                .displayName("TV")
                .householdId(source.getId())
                .authorizingAccountId(mover.getId())
                .build());
    var session =
        sessions.save(AuthSession.builder().accountId(mover.getId()).deviceName("web").build());
    var restorable =
        managerInvitations.save(
            pendingManagerInvitation(
                ManagerInvitationSpec.builder()
                    .profileId(UUID.randomUUID())
                    .recipientId(mover.getId())
                    .inviterId(UUID.randomUUID())
                    .build()));
    var proposal =
        managerInvitations.save(
            pendingManagerInvitation(
                ManagerInvitationSpec.builder()
                    .profileId(UUID.randomUUID())
                    .recipientId(UUID.randomUUID())
                    .inviterId(mover.getId())
                    .build()));
    var offered =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(UUID.randomUUID())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(mover.getId())
                .build());
    var issuedInvitation =
        accountInvitations.save(
            AccountInvitation.builder()
                .recipientEmail("issued@example.com")
                .issuerAccountId(mover.getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {1})
                .build());
    var profileInvitation =
        accountInvitations.save(
            AccountInvitation.builder()
                .recipientEmail("profile@example.com")
                .profileId(mover.getPersonalProfileId())
                .issuerAccountId(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {2})
                .build());
    var profileManagerInvitation =
        managerInvitations.save(
            pendingManagerInvitation(
                ManagerInvitationSpec.builder()
                    .profileId(mover.getPersonalProfileId())
                    .recipientId(UUID.randomUUID())
                    .inviterId(UUID.randomUUID())
                    .build()));
    var profileShareOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(mover.getPersonalProfileId())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(UUID.randomUUID())
                .build());
    var issuedReset =
        resetCodes.save(
            PasswordResetCode.builder()
                .accountId(UUID.randomUUID())
                .issuerAccountId(mover.getId())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {3})
                .build());
    shares.share(mover.getPersonalProfileId(), destination.getId(), false);
    var sourceViewer =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(source.getId())
                .selectedProfileId(mover.getPersonalProfileId())
                .deviceName("source viewer")
                .build());
    var destinationViewer =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(destination.getId())
                .selectedProfileId(mover.getPersonalProfileId())
                .deviceName("destination viewer")
                .build());

    var deleted =
        service.administrativelyDeleteAccount(
            identity(),
            AdministrativelyDeleteAccountCommand.builder()
                .accountId(mover.getId())
                .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                .reason("household dispute")
                .build());

    assertThat(deleted).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(mover.getId())).isEmpty();
    assertThat(profiles.findById(mover.getPersonalProfileId())).isEmpty();
    assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(managerInvitations.findById(restorable.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(proposal.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(shares.findById(offered.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(accountInvitations.findById(issuedInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(accountInvitations.findById(profileInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(
            managerInvitations.findById(profileManagerInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(shares.findById(profileShareOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(resetCodes.findById(issuedReset.getId()).orElseThrow().getStatus())
        .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
    assertThat(sessions.findById(sourceViewer.getId()).orElseThrow().getSelectedProfileId())
        .isNull();
    assertThat(sessions.findById(destinationViewer.getId()).orElseThrow().getSelectedProfileId())
        .isNull();
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("administrativelyDeleteAccount")
                .actorAccountId(identity().accountId())
                .reason("household dispute")
                .resource("accountId", mover.getId())
                .build());
  }

  @Test
  @DisplayName("Should keep the Profile when the replacement manager is eligible")
  void shouldKeepProfileWhenReplacementManagerIsEligible() {
    assertThat(rejectionOf(deleteKeeping(null)))
        .isInstanceOf(TransferRejections.ReplacementManagerRequired.class);
    assertThat(rejectionOf(deleteKeeping(UUID.randomUUID())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotFound.class);

    var elsewhere = residentOf(destination, HouseholdRole.ADMIN);
    assertThat(rejectionOf(deleteKeeping(elsewhere.getId())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotEligible.class);

    var anchor = residentOf(source, HouseholdRole.MEMBER);
    var kept = deleteKeeping(anchor.getId());

    assertThat(kept).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(mover.getId())).isEmpty();
    var preserved = profiles.findById(mover.getPersonalProfileId()).orElseThrow();
    assertThat(preserved.getHouseholdId()).isEqualTo(source.getId());
    assertThat(managers.existsByAccountIdAndProfileId(anchor.getId(), preserved.getId())).isTrue();
    var availability =
        shares
            .findByProfileIdAndHouseholdIdAndStatus(
                preserved.getId(), source.getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(availability.isStructural()).isFalse();
  }

  @Test
  @DisplayName("Should reject the replacement when the deleted Account is named")
  void shouldRejectReplacementWhenDeletedAccountIsNamed() {
    assertThat(rejectionOf(deleteKeeping(mover.getId())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should require a HouseholdAdmin replacement when the Profile is restricted")
  void shouldRequireHouseholdAdminReplacementWhenProfileIsRestricted() {
    profiles.findById(mover.getPersonalProfileId()).orElseThrow().setMaximumAllowedRatingAge(13);
    var member = residentOf(source, HouseholdRole.MEMBER);

    assertThat(rejectionOf(deleteKeeping(member.getId())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should require a reason before reauthentication when deletion is requested")
  void shouldRequireReasonBeforeReauthenticationWhenDeletionIsRequested() {
    assertThat(
            rejectionOf(
                service.administrativelyDeleteAccount(
                    identity(),
                    AdministrativelyDeleteAccountCommand.builder()
                        .accountId(mover.getId())
                        .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                        .reason(" ")
                        .build())))
        .isInstanceOf(TransferRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();

    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyDeleteAccount
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(
            rejectionOf(
                service.administrativelyDeleteAccount(
                    identity(),
                    AdministrativelyDeleteAccountCommand.builder()
                        .accountId(mover.getId())
                        .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                        .reason("dispute")
                        .build())))
        .isInstanceOf(TransferRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should hide the Account when deletion is unauthorized")
  void shouldHideAccountWhenDeletionIsUnauthorized() {
    authorization.denyAll();

    assertThat(
            rejectionOf(
                service.administrativelyDeleteAccount(
                    identity(),
                    AdministrativelyDeleteAccountCommand.builder()
                        .accountId(mover.getId())
                        .profileCleanup(ProfileCleanup.ERASE_PROFILE)
                        .reason("dispute")
                        .build())))
        .isInstanceOf(TransferRejections.AccountNotFound.class);
    assertThat(accounts.findById(mover.getId())).isPresent();
  }

  @Test
  @DisplayName("Should forbid deletion when the unauthorized Account remains visible")
  void shouldForbidDeletionWhenUnauthorizedAccountRemainsVisible() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewAccountAdministration
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
    var actor = identity();
    var command =
        AdministrativelyDeleteAccountCommand.builder()
            .accountId(mover.getId())
            .profileCleanup(ProfileCleanup.ERASE_PROFILE)
            .reason("dispute")
            .build();

    assertThatThrownBy(() -> service.administrativelyDeleteAccount(actor, command))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(accounts.findById(mover.getId())).isPresent();
  }

  @Test
  @DisplayName("Should forbid self-deletion when authorization is denied by policy")
  void shouldForbidSelfDeletionWhenAuthorizationIsDeniedByPolicy() {
    var self = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(mover.getId()).build();
    authorization.denyAll();

    assertThatThrownBy(() -> service.deleteMyAccount(self, "DELETE"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(accounts.findById(mover.getId())).isPresent();
  }

  @Test
  @DisplayName("Should erase the Account and artifacts when a person deletes their own Account")
  void shouldEraseAccountAndArtifactsWhenPersonDeletesOwnAccount() {
    var self = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(mover.getId()).build();
    var session =
        sessions.save(AuthSession.builder().accountId(mover.getId()).deviceName("web").build());
    var registration =
        registrations.save(
            DeviceRegistration.builder()
                .esn("self-delete")
                .displayName("TV")
                .householdId(source.getId())
                .authorizingAccountId(mover.getId())
                .build());
    var invitation =
        accountInvitations.save(pendingAccountInvitation(mover.getPersonalProfileId()));
    var managerInvitation =
        managerInvitations.save(
            pendingManagerInvitation(
                ManagerInvitationSpec.builder()
                    .profileId(mover.getPersonalProfileId())
                    .recipientId(UUID.randomUUID())
                    .inviterId(UUID.randomUUID())
                    .build()));

    assertThat(service.deleteMyAccount(self, "DELETE")).isInstanceOf(Outcome.Accepted.class);
    assertThat(accounts.findById(mover.getId())).isEmpty();
    assertThat(profiles.findById(mover.getPersonalProfileId())).isEmpty();
    assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNotNull();
    assertThat(registrations.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(accountInvitations.findById(invitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(managerInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("deleteMyAccount")
                .actorAccountId(mover.getId())
                .reason("self-deletion")
                .resource("accountId", mover.getId())
                .build());
  }

  @Test
  @DisplayName("Should reserve the final Account when a person deletes their own Account")
  void shouldReserveFinalAccountWhenPersonDeletesOwnAccount() {
    var lonerHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var loner = residentOf(lonerHousehold, HouseholdRole.ADMIN);
    var lonerIdentity =
        AuthenticatedIdentityFixture.accountScopedBuilder().accountId(loner.getId()).build();
    assertThat(rejectionOf(service.deleteMyAccount(lonerIdentity, "DELETE")))
        .isInstanceOf(TransferRejections.FinalAccount.class);
  }

  @Test
  @DisplayName("Should require literal confirmation when a person deletes their own Account")
  void shouldRequireLiteralConfirmationWhenPersonDeletesOwnAccount() {
    var self = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(mover.getId()).build();

    assertThat(rejectionOf(service.deleteMyAccount(self, "delete")))
        .isInstanceOf(TransferRejections.ConfirmationRequired.class);
    assertThat(accounts.findById(mover.getId())).isPresent();
  }

  private Outcome<UUID, TransferRejections.AdministrativelyDeleteAccount> deleteKeeping(
      UUID replacement) {
    return service.administrativelyDeleteAccount(
        identity(),
        AdministrativelyDeleteAccountCommand.builder()
            .accountId(mover.getId())
            .profileCleanup(ProfileCleanup.PRESERVE_PROFILE)
            .replacementManagerAccountId(replacement)
            .reason("moving on")
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

  private Optional<ProfileHouseholdShare> structuralShareIn(UUID householdId) {
    return shares
        .findByProfileIdAndHouseholdIdAndStatus(
            mover.getPersonalProfileId(), householdId, ProfileShareStatus.ACTIVE)
        .filter(ProfileHouseholdShare::isStructural);
  }

  private ProfileManagerInvitation pendingManagerInvitation(ManagerInvitationSpec invitation) {
    return ProfileManagerInvitation.builder()
        .profileId(invitation.profileId())
        .profileName("Joe")
        .inviterAccountId(invitation.inviterId())
        .inviterDisplayName("Inviter")
        .recipientAccountId(invitation.recipientId())
        .recipientEmail("recipient@example.com")
        .expiresAt(Instant.now().plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1})
        .build();
  }

  private AccountInvitation pendingAccountInvitation(UUID profileId) {
    return AccountInvitation.builder()
        .recipientEmail("profile@example.com")
        .profileId(profileId)
        .issuerAccountId(UUID.randomUUID())
        .expiresAt(Instant.now().plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1})
        .build();
  }

  @Builder
  private record ManagerInvitationSpec(UUID profileId, UUID recipientId, UUID inviterId) {}

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
}
