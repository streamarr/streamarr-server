package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fakes.FakeAccountInvitationRepository;
import com.streamarr.server.fakes.FakeAuthSessionRepository;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeHouseholdRepository;
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
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
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

/**
 * Unlinked-Profile transfers and force-deletion over fakes: the destination anchor is named up
 * front, pending Profile-bound proposals never survive the move, and a linked Profile refuses both
 * operations.
 */
@Tag("UnitTest")
@DisplayName("Profile Lifecycle Service Tests")
class ProfileLifecycleServiceTest {

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
  private final FakeAuthSessionRepository sessions = new FakeAuthSessionRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final ProfileLifecycleService service =
      new ProfileLifecycleService(
          authorization,
          profiles,
          accounts,
          households,
          managers,
          managerInvitations,
          accountInvitations,
          shares,
          sessions,
          audit,
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          Clock.systemUTC());

  private Household source;
  private Household destination;
  private Profile orphan;
  private UserAccount destinationAnchor;

  @BeforeEach
  void setUp() {
    source = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    destination = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    orphan =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().householdId(source.getId()).name("Joe").build());
    shares.share(orphan.getId(), source.getId(), false);
    destinationAnchor = residentOf(destination);
  }

  @Test
  @DisplayName(
      "Should move the Profile and reset proposals when the destination anchor is eligible")
  void shouldMoveProfileAndResetProposalsWhenDestinationAnchorIsEligible() {
    var pendingOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    var connectInvitation = accountInvitations.save(pendingAccountInvitation(orphan.getId()));
    var managerInvitation = managerInvitations.save(pendingManagerInvitation(orphan.getId()));
    var visitingHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    var activeVisit = shares.share(orphan.getId(), visitingHousehold.getId(), false);
    var watching =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(source.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("tv")
                .build());

    var moved =
        service.transferProfile(
            identity(),
            TransferProfileCommand.builder()
                .profileId(orphan.getId())
                .destinationHouseholdId(destination.getId())
                .localManagerAccountId(destinationAnchor.getId())
                .reason("recovery")
                .build());

    assertThat(moved).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(orphan.getId()).orElseThrow().getHouseholdId())
        .isEqualTo(destination.getId());
    assertThat(managers.existsByAccountIdAndProfileId(destinationAnchor.getId(), orphan.getId()))
        .isTrue();
    assertThat(
            shares.findByProfileIdAndHouseholdIdAndStatus(
                orphan.getId(), destination.getId(), ProfileShareStatus.ACTIVE))
        .isPresent();
    assertThat(
            shares.findByProfileIdAndHouseholdIdAndStatus(
                orphan.getId(), source.getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();
    assertThat(shares.findById(pendingOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(accountInvitations.findById(connectInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(managerInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(shares.findById(activeVisit.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(sessions.findById(watching.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("transferProfile")
                .actorAccountId(identity().accountId())
                .reason("recovery")
                .resource("profileId", orphan.getId())
                .build());
  }

  @Test
  @DisplayName("Should reject the transfer when the destination anchor is invalid")
  void shouldRejectTransferWhenDestinationAnchorIsInvalid() {
    assertThat(rejectionOf(transferWithAnchor(null)))
        .isInstanceOf(TransferRejections.LocalManagerRequired.class);
    assertThat(rejectionOf(transferWithAnchor(UUID.randomUUID())))
        .isInstanceOf(TransferRejections.LocalManagerNotFound.class);

    var stranger = residentOf(households.save(HouseholdFixture.defaultHouseholdBuilder().build()));
    assertThat(rejectionOf(transferWithAnchor(stranger.getId())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotEligible.class);

    var restrictedAnchor = residentOf(destination);
    profiles
        .findById(restrictedAnchor.getPersonalProfileId())
        .orElseThrow()
        .setMaximumAllowedRatingAge(13);
    assertThat(rejectionOf(transferWithAnchor(restrictedAnchor.getId())))
        .isInstanceOf(TransferRejections.ReplacementManagerNotEligible.class);
  }

  @Test
  @DisplayName("Should reserve the Profile for Account operations when the Profile is linked")
  void shouldReserveProfileForAccountOperationsWhenProfileIsLinked() {
    var linked = residentOf(source);

    assertThat(
            rejectionOf(
                service.transferProfile(
                    identity(),
                    TransferProfileCommand.builder()
                        .profileId(linked.getPersonalProfileId())
                        .destinationHouseholdId(destination.getId())
                        .localManagerAccountId(destinationAnchor.getId())
                        .build())))
        .isInstanceOf(TransferRejections.ProfileLinked.class);
    assertThat(
            rejectionOf(
                service.administrativelyDeleteProfile(
                    identity(), linked.getPersonalProfileId(), "cleanup")))
        .isInstanceOf(TransferRejections.ProfileLinked.class);
  }

  @Test
  @DisplayName("Should require a reason when a Profile is administratively deleted")
  void shouldRequireReasonWhenProfileIsAdministrativelyDeleted() {
    assertThat(rejectionOf(service.administrativelyDeleteProfile(identity(), orphan.getId(), " ")))
        .isInstanceOf(TransferRejections.ReasonRequired.class);
    assertThat(profiles.findById(orphan.getId())).isPresent();
  }

  @Test
  @DisplayName(
      "Should delete the Profile and dependent state when administrative deletion succeeds")
  void shouldDeleteProfileAndDependentStateWhenAdministrativeDeletionSucceeds() {
    var otherHousehold = households.save(HouseholdFixture.defaultHouseholdBuilder().build());
    shares.share(orphan.getId(), otherHousehold.getId(), false);
    var homeViewer =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(source.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("tv")
                .build());
    var visitor =
        sessions.save(
            AuthSession.builder()
                .accountId(UUID.randomUUID())
                .contextHouseholdId(otherHousehold.getId())
                .selectedProfileId(orphan.getId())
                .deviceName("phone")
                .build());
    var pendingOffer =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build());
    var connectInvitation = accountInvitations.save(pendingAccountInvitation(orphan.getId()));
    var managerInvitation = managerInvitations.save(pendingManagerInvitation(orphan.getId()));

    var deleted = service.administrativelyDeleteProfile(identity(), orphan.getId(), "abuse report");

    assertThat(deleted).isInstanceOf(Outcome.Accepted.class);
    assertThat(profiles.findById(orphan.getId())).isEmpty();
    assertThat(sessions.findById(homeViewer.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(sessions.findById(visitor.getId()).orElseThrow().getSelectedProfileId()).isNull();
    assertThat(shares.findById(pendingOffer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(accountInvitations.findById(connectInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInvitationStatus.INVALIDATED);
    assertThat(managerInvitations.findById(managerInvitation.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(audit.entries())
        .containsExactly(
            SecurityAuditEntry.builder()
                .operation("administrativelyDeleteProfile")
                .actorAccountId(identity().accountId())
                .reason("abuse report")
                .resource("profileId", orphan.getId())
                .build());
  }

  @Test
  @DisplayName("Should return ProfileNotFound when the Profile was already deleted")
  void shouldReturnProfileNotFoundWhenProfileWasAlreadyDeleted() {
    assertThat(service.administrativelyDeleteProfile(identity(), orphan.getId(), "cleanup"))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(
            rejectionOf(service.administrativelyDeleteProfile(identity(), orphan.getId(), "again")))
        .isInstanceOf(TransferRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should hide the Profile when transfer is unauthorized")
  void shouldHideProfileWhenTransferIsUnauthorized() {
    authorization.denyAll();
    assertThat(rejectionOf(transferWithAnchor(destinationAnchor.getId())))
        .isInstanceOf(TransferRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should reject an authorized transfer when the Profile does not exist")
  void shouldRejectAuthorizedTransferWhenProfileDoesNotExist() {
    assertThat(
            rejectionOf(
                service.transferProfile(
                    identity(),
                    TransferProfileCommand.builder()
                        .profileId(UUID.randomUUID())
                        .destinationHouseholdId(destination.getId())
                        .localManagerAccountId(destinationAnchor.getId())
                        .build())))
        .isInstanceOf(TransferRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should return HouseholdNotFound when the transfer destination does not exist")
  void shouldReturnHouseholdNotFoundWhenTransferDestinationDoesNotExist() {
    assertThat(
            rejectionOf(
                service.transferProfile(
                    identity(),
                    TransferProfileCommand.builder()
                        .profileId(orphan.getId())
                        .destinationHouseholdId(UUID.randomUUID())
                        .localManagerAccountId(destinationAnchor.getId())
                        .build())))
        .isInstanceOf(TransferRejections.HouseholdNotFound.class);
  }

  @Test
  @DisplayName("Should reject the transfer when the destination is the current Household")
  void shouldRejectTransferWhenDestinationIsCurrentHousehold() {
    assertThat(
            rejectionOf(
                service.transferProfile(
                    identity(),
                    TransferProfileCommand.builder()
                        .profileId(orphan.getId())
                        .destinationHouseholdId(source.getId())
                        .localManagerAccountId(destinationAnchor.getId())
                        .build())))
        .isInstanceOf(TransferRejections.SameHousehold.class);
  }

  private Outcome<Profile, TransferRejections.TransferProfile> transferWithAnchor(UUID anchorId) {
    return service.transferProfile(
        identity(),
        TransferProfileCommand.builder()
            .profileId(orphan.getId())
            .destinationHouseholdId(destination.getId())
            .localManagerAccountId(anchorId)
            .build());
  }

  private UserAccount residentOf(Household household) {
    var account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .householdId(household.getId())
                .householdRole(HouseholdRole.MEMBER)
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

  private ProfileManagerInvitation pendingManagerInvitation(UUID profileId) {
    return ProfileManagerInvitation.builder()
        .profileId(profileId)
        .profileName("Joe")
        .inviterAccountId(UUID.randomUUID())
        .inviterDisplayName("Inviter")
        .recipientAccountId(UUID.randomUUID())
        .recipientEmail("recipient@example.com")
        .expiresAt(Instant.now().plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1})
        .build();
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
