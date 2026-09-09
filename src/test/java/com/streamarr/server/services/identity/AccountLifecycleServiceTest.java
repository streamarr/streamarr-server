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
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
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
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Account transfers and deletion over fakes: the Account and its Personal Profile move together,
 * the old Household reads END or KEEP_AS_VISITOR exactly as chosen, and deletion leaves no session,
 * registration, or pending proposal behind.
 */
@Tag("UnitTest")
@DisplayName("Account Lifecycle Service Tests")
class AccountLifecycleServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

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
          Clock.fixed(NOW, ZoneOffset.UTC));

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
    assertThat(service.transferAccount(identity(), transferCommand().build()))
        .isEqualTo(Outcome.accepted(mover));

    assertThat(accounts.findById(mover.getId()))
        .get()
        .satisfies(
            account -> {
              assertThat(account.getHouseholdId()).isEqualTo(destination.getId());
              assertThat(account.getHouseholdRole()).isEqualTo(HouseholdRole.ADMIN);
            });
    assertThat(profiles.findById(mover.getPersonalProfileId()))
        .get()
        .extracting(Profile::getHouseholdId)
        .isEqualTo(destination.getId());
    assertThat(structuralShareIn(destination.getId())).isPresent();
    assertThat(
            shares.findByProfileIdAndHouseholdIdAndStatus(
                mover.getPersonalProfileId(), source.getId(), ProfileShareStatus.ENDED))
        .isPresent();
  }

  @ParameterizedTest
  @EnumSource(SourceHouseholdAccess.class)
  @DisplayName("Should reset playback selection when an Account transfers Households")
  void shouldResetPlaybackSelectionWhenAccountTransfersHouseholds(SourceHouseholdAccess access) {
    var watching = sessions.save(watchingSession().build());

    assertThat(
            service.transferAccount(
                identity(), transferCommand().sourceHouseholdAccess(access).build()))
        .isEqualTo(Outcome.accepted(mover));

    assertThat(sessions.findById(watching.getId()))
        .get()
        .satisfies(
            session -> {
              assertThat(session.getSelectedProfileId()).isNull();
              assertThat(session.getRevokedAt()).isNull();
              assertThat(session.getContextHouseholdId())
                  .isEqualTo(access == SourceHouseholdAccess.END ? null : source.getId());
            });
  }

  @ParameterizedTest
  @EnumSource(SourceHouseholdAccess.class)
  @DisplayName("Should retain device authorization only for a kept visit when an Account transfers")
  void shouldRetainDeviceAuthorizationOnlyForKeptVisitWhenAccountTransfers(
      SourceHouseholdAccess access) {
    var registration = registrations.save(authorizedDevice().build());

    assertThat(
            service.transferAccount(
                identity(), transferCommand().sourceHouseholdAccess(access).build()))
        .isEqualTo(Outcome.accepted(mover));

    assertThat(registrations.findById(registration.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(
            access == SourceHouseholdAccess.END
                ? DeviceRegistrationStatus.REVOKED
                : DeviceRegistrationStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should record the actor and reason when an Account transfers")
  void shouldRecordActorAndReasonWhenAccountTransfers() {
    assertThat(service.transferAccount(identity(), transferCommand().reason("support").build()))
        .isEqualTo(Outcome.accepted(mover));

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
    assertThat(
            service.transferAccount(
                identity(),
                transferCommand()
                    .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                    .build()))
        .isEqualTo(Outcome.accepted(mover));

    assertThat(
            shares.findByProfileIdAndHouseholdIdAndStatus(
                mover.getPersonalProfileId(), source.getId(), ProfileShareStatus.ACTIVE))
        .get()
        .extracting(ProfileHouseholdShare::isStructural)
        .isEqualTo(false);
    assertThat(structuralShareIn(destination.getId())).isPresent();
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

  @ParameterizedTest
  @EnumSource(DeletionCaller.class)
  @DisplayName("Should erase the Account and Personal Profile when deletion is accepted")
  void shouldEraseAccountAndPersonalProfileWhenDeletionIsAccepted(DeletionCaller caller) {
    deleteAccount(caller);

    assertThat(accounts.findById(mover.getId())).isEmpty();
    assertThat(profiles.findById(mover.getPersonalProfileId())).isEmpty();
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation(
                    caller == DeletionCaller.SELF
                        ? "deleteMyAccount"
                        : "administrativelyDeleteAccount")
                .actorAccountId(
                    caller == DeletionCaller.SELF ? mover.getId() : identity().accountId())
                .reason(caller == DeletionCaller.SELF ? "self-deletion" : "household dispute")
                .resource("accountId", mover.getId())
                .build());
  }

  @ParameterizedTest
  @EnumSource(DeletionCaller.class)
  @DisplayName("Should revoke only the deleted Account's sessions when deletion is accepted")
  void shouldRevokeOnlyDeletedAccountSessionsWhenDeletionIsAccepted(DeletionCaller caller) {
    var session = sessions.save(watchingSession().build());
    var unrelated =
        sessions.save(
            watchingSession()
                .accountId(UUID.randomUUID())
                .selectedProfileId(UUID.randomUUID())
                .build());

    deleteAccount(caller);

    assertThat(sessions.findById(session.getId()))
        .get()
        .extracting(AuthSession::getRevokedAt)
        .isEqualTo(NOW);
    assertThat(sessions.findById(unrelated.getId()))
        .get()
        .extracting(AuthSession::getRevokedAt)
        .isNull();
  }

  @ParameterizedTest
  @EnumSource(DeletionCaller.class)
  @DisplayName("Should revoke only the deleted Account's devices when deletion is accepted")
  void shouldRevokeOnlyDeletedAccountDevicesWhenDeletionIsAccepted(DeletionCaller caller) {
    var target = registrations.save(authorizedDevice().build());
    var unrelated =
        registrations.save(
            authorizedDevice().esn("other").authorizingAccountId(UUID.randomUUID()).build());

    deleteAccount(caller);

    assertThat(registrations.findById(target.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(registrations.findById(unrelated.getId()))
        .get()
        .extracting(DeviceRegistration::getStatus)
        .isEqualTo(DeviceRegistrationStatus.ACTIVE);
  }

  @ParameterizedTest
  @CsvSource({
    "ADMIN, ISSUER",
    "ADMIN, RECIPIENT",
    "ADMIN, PROFILE",
    "SELF, ISSUER",
    "SELF, RECIPIENT",
    "SELF, PROFILE"
  })
  @DisplayName(
      "Should invalidate a manager invitation when deletion removes a participant or Profile")
  void shouldInvalidateManagerInvitationWhenDeletionRemovesParticipantOrProfile(
      DeletionCaller caller, ArtifactBinding binding) {
    var fixture = managerInvitation();
    switch (binding) {
      case ISSUER -> fixture.inviterAccountId(mover.getId());
      case RECIPIENT -> fixture.recipientAccountId(mover.getId());
      case PROFILE -> fixture.profileId(mover.getPersonalProfileId());
    }

    var target = managerInvitations.save(fixture.build());
    var unrelated = managerInvitations.save(managerInvitation().build());

    deleteAccount(caller);

    assertThat(managerInvitations.findById(target.getId()))
        .get()
        .extracting(ProfileManagerInvitation::getStatus)
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(unrelated.getId()))
        .get()
        .extracting(ProfileManagerInvitation::getStatus)
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @ParameterizedTest
  @CsvSource({"ADMIN, ISSUER", "ADMIN, PROFILE", "SELF, ISSUER", "SELF, PROFILE"})
  @DisplayName(
      "Should invalidate an Account invitation when deletion removes its issuer or Profile")
  void shouldInvalidateAccountInvitationWhenDeletionRemovesIssuerOrProfile(
      DeletionCaller caller, ArtifactBinding binding) {
    var fixture = accountInvitation();
    if (binding == ArtifactBinding.ISSUER) {
      fixture.issuerAccountId(mover.getId());
    }

    if (binding == ArtifactBinding.PROFILE) {
      fixture.profileId(mover.getPersonalProfileId());
    }

    var target = accountInvitations.save(fixture.build());
    var unrelated = accountInvitations.save(accountInvitation().build());

    deleteAccount(caller);

    assertThat(accountInvitations.findById(target.getId()))
        .get()
        .extracting(AccountInvitation::getStatus)
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(accountInvitations.findById(unrelated.getId()))
        .get()
        .extracting(AccountInvitation::getStatus)
        .isEqualTo(AccountInvitationStatus.PENDING);
  }

  @ParameterizedTest
  @CsvSource({"ADMIN, ISSUER", "ADMIN, PROFILE", "SELF, ISSUER", "SELF, PROFILE"})
  @DisplayName("Should invalidate a share offer when deletion removes its offerer or Profile")
  void shouldInvalidateShareOfferWhenDeletionRemovesOffererOrProfile(
      DeletionCaller caller, ArtifactBinding binding) {
    var fixture = shareOffer();
    if (binding == ArtifactBinding.ISSUER) {
      fixture.offeredByAccountId(mover.getId());
    }

    if (binding == ArtifactBinding.PROFILE) {
      fixture.profileId(mover.getPersonalProfileId());
    }

    var target = shares.save(fixture.build());
    var unrelated = shares.save(shareOffer().build());

    deleteAccount(caller);

    assertThat(shares.findById(target.getId()))
        .get()
        .extracting(ProfileHouseholdShare::getStatus)
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(shares.findById(unrelated.getId()))
        .get()
        .extracting(ProfileHouseholdShare::getStatus)
        .isEqualTo(ProfileShareStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(DeletionCaller.class)
  @DisplayName("Should invalidate issued reset codes when deleting their issuer")
  void shouldInvalidateIssuedResetCodesWhenDeletingIssuer(DeletionCaller caller) {
    var target =
        resetCodes.save(
            PasswordResetCode.builder()
                .accountId(UUID.randomUUID())
                .issuerAccountId(mover.getId())
                .expiresAt(NOW.plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {3})
                .build());

    deleteAccount(caller);

    assertThat(resetCodes.findById(target.getId()))
        .get()
        .extracting(PasswordResetCode::getStatus)
        .isEqualTo(PasswordResetCodeStatus.INVALIDATED);
  }

  @ParameterizedTest
  @EnumSource(DeletionCaller.class)
  @DisplayName(
      "Should clear selections across Households when deletion removes the selected Profile")
  void shouldClearSelectionsAcrossHouseholdsWhenDeletionRemovesSelectedProfile(
      DeletionCaller caller) {
    shares.share(mover.getPersonalProfileId(), destination.getId(), false);
    var sourceViewer = sessions.save(watchingSession().accountId(UUID.randomUUID()).build());
    var destinationViewer =
        sessions.save(
            watchingSession()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(destination.getId())
                .build());
    var unrelatedProfileId = UUID.randomUUID();
    var unrelated =
        sessions.save(
            watchingSession()
                .accountId(UUID.randomUUID())
                .selectedProfileId(unrelatedProfileId)
                .build());

    deleteAccount(caller);

    assertThat(sessions.findById(sourceViewer.getId()))
        .get()
        .extracting(AuthSession::getSelectedProfileId)
        .isNull();
    assertThat(sessions.findById(destinationViewer.getId()))
        .get()
        .extracting(AuthSession::getSelectedProfileId)
        .isNull();
    assertThat(sessions.findById(unrelated.getId()))
        .get()
        .extracting(AuthSession::getSelectedProfileId)
        .isEqualTo(unrelatedProfileId);
  }

  @Test
  @DisplayName("Should keep the Profile when the replacement manager is eligible")
  void shouldKeepProfileWhenReplacementManagerIsEligible() {
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

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  @DisplayName("Should preserve the Account when administrative deletion lacks a reason")
  void shouldPreserveAccountWhenAdministrativeDeletionLacksReason(String reason) {
    assertThat(
            service.administrativelyDeleteAccount(
                identity(), deletionCommand().reason(reason).build()))
        .isEqualTo(Outcome.rejected(new TransferRejections.ReasonRequired()));
    assertThat(accounts.findById(mover.getId())).isPresent();
    assertThat(profiles.findById(mover.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the Account when administrative deletion requires reauthentication")
  void shouldPreserveAccountWhenAdministrativeDeletionRequiresReauthentication() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyDeleteAccount
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));

    assertThat(service.administrativelyDeleteAccount(identity(), deletionCommand().build()))
        .isEqualTo(Outcome.rejected(new TransferRejections.ReauthenticationRequired()));
    assertThat(accounts.findById(mover.getId())).isPresent();
    assertThat(profiles.findById(mover.getPersonalProfileId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the Account when a replacement manager is missing from the request")
  void shouldPreserveAccountWhenReplacementManagerIsMissingFromRequest() {
    assertThat(deleteKeeping(null))
        .isEqualTo(Outcome.rejected(new TransferRejections.ReplacementManagerRequired()));
    assertThat(accounts.findById(mover.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the Account when the replacement manager does not exist")
  void shouldPreserveAccountWhenReplacementManagerDoesNotExist() {
    assertThat(deleteKeeping(UUID.randomUUID()))
        .isEqualTo(Outcome.rejected(new TransferRejections.ReplacementManagerNotFound()));
    assertThat(accounts.findById(mover.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the Account when the replacement manager lives elsewhere")
  void shouldPreserveAccountWhenReplacementManagerLivesElsewhere() {
    var elsewhere = residentOf(destination, HouseholdRole.ADMIN);
    assertThat(deleteKeeping(elsewhere.getId()))
        .isEqualTo(Outcome.rejected(new TransferRejections.ReplacementManagerNotEligible()));
    assertThat(accounts.findById(mover.getId())).isPresent();
    assertThat(audit.entries()).isEmpty();
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

  private TransferAccountCommand.TransferAccountCommandBuilder transferCommand() {
    return TransferAccountCommand.builder()
        .accountId(mover.getId())
        .destinationHouseholdId(destination.getId())
        .sourceHouseholdAccess(SourceHouseholdAccess.END);
  }

  private AdministrativelyDeleteAccountCommand.AdministrativelyDeleteAccountCommandBuilder
      deletionCommand() {
    return AdministrativelyDeleteAccountCommand.builder()
        .accountId(mover.getId())
        .profileCleanup(ProfileCleanup.ERASE_PROFILE)
        .reason("household dispute");
  }

  private void deleteAccount(DeletionCaller caller) {
    var result =
        switch (caller) {
          case ADMIN ->
              service.administrativelyDeleteAccount(identity(), deletionCommand().build());
          case SELF ->
              service.deleteMyAccount(
                  AuthenticatedIdentityFixture.accountScopedBuilder()
                      .accountId(mover.getId())
                      .build(),
                  "DELETE");
        };
    assertThat(result).isEqualTo(Outcome.accepted(mover.getId()));
  }

  private AuthSession.AuthSessionBuilder<?, ?> watchingSession() {
    return AuthSession.builder()
        .accountId(mover.getId())
        .contextHouseholdId(source.getId())
        .selectedProfileId(mover.getPersonalProfileId())
        .deviceName("web");
  }

  private DeviceRegistration.DeviceRegistrationBuilder<?, ?> authorizedDevice() {
    return DeviceRegistration.builder()
        .esn("esn-1")
        .displayName("TV")
        .householdId(source.getId())
        .authorizingAccountId(mover.getId());
  }

  private ProfileManagerInvitation.ProfileManagerInvitationBuilder<?, ?> managerInvitation() {
    return ProfileManagerInvitation.builder()
        .profileId(UUID.randomUUID())
        .profileName("Joe")
        .inviterAccountId(UUID.randomUUID())
        .inviterDisplayName("Inviter")
        .recipientAccountId(UUID.randomUUID())
        .recipientEmail("recipient@example.com")
        .expiresAt(NOW.plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1});
  }

  private AccountInvitation.AccountInvitationBuilder<?, ?> accountInvitation() {
    return AccountInvitation.builder()
        .recipientEmail("profile@example.com")
        .profileId(UUID.randomUUID())
        .issuerAccountId(UUID.randomUUID())
        .expiresAt(NOW.plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1});
  }

  private ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> shareOffer() {
    return ProfileHouseholdShare.builder()
        .profileId(UUID.randomUUID())
        .householdId(UUID.randomUUID())
        .status(ProfileShareStatus.PENDING)
        .offeredByAccountId(UUID.randomUUID());
  }

  private enum DeletionCaller {
    ADMIN,
    SELF
  }

  private enum ArtifactBinding {
    ISSUER,
    RECIPIENT,
    PROFILE
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
}
