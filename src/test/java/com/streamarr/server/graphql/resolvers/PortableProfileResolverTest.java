package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.graphql.inputs.PortableProfileInputs;
import com.streamarr.server.services.auth.AccountHouseholdTransferCommand;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CapturingHouseholdAdministrationService;
import com.streamarr.server.services.auth.CapturingProfileDeletionService;
import com.streamarr.server.services.auth.CapturingServerAdministrationService;
import com.streamarr.server.services.auth.CreatePortableProfileCommand;
import com.streamarr.server.services.auth.DeleteProfileCommand;
import com.streamarr.server.services.auth.ForceProfileDeletionCommand;
import com.streamarr.server.services.auth.ForceProfileUnshareCommand;
import com.streamarr.server.services.auth.HouseholdOwnershipTransferCommand;
import com.streamarr.server.services.auth.HouseholdProfileRemoval;
import com.streamarr.server.services.auth.PortableIdentityService;
import com.streamarr.server.services.auth.ProfileHomeDeparture;
import com.streamarr.server.services.auth.ProfileManagementRelinquishment;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfileManagerInvitationAcceptance;
import com.streamarr.server.services.auth.ProfileManagerInvitationCancellation;
import com.streamarr.server.services.auth.ProfileManagerInvitationRejection;
import com.streamarr.server.services.auth.ProfileManagerInvite;
import com.streamarr.server.services.auth.ProfileManagerOverrideAction;
import com.streamarr.server.services.auth.ProfileManagerOverrideCommand;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.auth.ProfilePolicyService;
import com.streamarr.server.services.auth.ProfileShareAcceptance;
import com.streamarr.server.services.auth.ProfileShareCancellation;
import com.streamarr.server.services.auth.ProfileShareOffer;
import com.streamarr.server.services.auth.ProfileShareRejection;
import com.streamarr.server.services.auth.ProfileSharingService;
import com.streamarr.server.services.auth.RemoveProfileContentCeilingCommand;
import com.streamarr.server.services.auth.RenamePortableProfileCommand;
import com.streamarr.server.services.auth.ResetProfilePinCommand;
import com.streamarr.server.services.auth.SetProfileContentCeilingCommand;
import com.streamarr.server.services.auth.SetProfileKindCommand;
import com.streamarr.server.services.auth.TokenScope;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("UnitTest")
@DisplayName("Portable Profile Resolver Tests")
class PortableProfileResolverTest {

  @Test
  @DisplayName("Should derive portable profile creator and hash PIN at GraphQL boundary")
  void shouldDerivePortableProfileCreatorAndHashPinAtGraphQlBoundary() {
    var fixture = new ResolverFixture();

    var result =
        fixture.resolver.createPortableProfile(
            new PortableProfileInputs.ProfileCreation("Global Kai", ProfileKind.KID, 7, "1234"));

    assertThat(result.name()).isEqualTo("Global Kai");
    assertThat(result.kind()).isEqualTo(ProfileKind.KID);
    assertThat(result.maximumAllowedRatingAge()).isEqualTo(7);
    assertThat(result.pinProtected()).isTrue();
    assertThat(fixture.managementService.creation)
        .isEqualTo(
            CreatePortableProfileCommand.builder()
                .authority(fixture.identity)
                .name("Global Kai")
                .kind(ProfileKind.KID)
                .maximumAllowedRatingAge(7)
                .pinHash("1234")
                .build());
  }

  @Test
  @DisplayName("Should create portable profile without PIN when PIN omitted")
  void shouldCreatePortableProfileWithoutPinWhenPinOmitted() {
    var fixture = new ResolverFixture();

    var result =
        fixture.resolver.createPortableProfile(
            new PortableProfileInputs.ProfileCreation("Guest", ProfileKind.ADULT, null, null));

    assertThat(result.pinProtected()).isFalse();
    assertThat(fixture.managementService.creation.pinHash()).isNull();
  }

  @Test
  @DisplayName("Should reject blank PIN when creating portable profile")
  void shouldRejectBlankPinWhenCreatingPortableProfile() {
    var fixture = new ResolverFixture();
    var input = new PortableProfileInputs.ProfileCreation("Guest", ProfileKind.ADULT, null, "");

    assertThatThrownBy(() -> fixture.resolver.createPortableProfile(input))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should rename portable profile as authenticated account")
  void shouldRenamePortableProfileAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.renamePortableProfile(
                new PortableProfileInputs.ProfileRename(profileId.toString(), "Renamed")))
        .isTrue();
    assertThat(fixture.managementService.rename)
        .isEqualTo(
            RenamePortableProfileCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .name("Renamed")
                .build());
  }

  @Test
  @DisplayName("Should reject malformed portable profile identifier")
  void shouldRejectMalformedPortableProfileIdentifier() {
    var fixture = new ResolverFixture();
    var input = new PortableProfileInputs.ProfileRename("not-a-uuid", "Renamed");

    assertThatThrownBy(() -> fixture.resolver.renamePortableProfile(input))
        .isInstanceOf(InvalidIdException.class);
  }

  @Test
  @DisplayName("Should offer profile share as authenticated account")
  void shouldOfferProfileShareAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();
    var householdId = UUID.randomUUID();

    var result =
        fixture.resolver.offerProfileShare(
            new PortableProfileInputs.ShareOffer(profileId.toString(), householdId.toString()));

    assertThat(result.profileId()).isEqualTo(fixture.sharingService.shareResult.getProfileId());
    assertThat(result.householdId()).isEqualTo(fixture.sharingService.shareResult.getHouseholdId());
    assertThat(result.status()).isEqualTo(fixture.sharingService.shareResult.getStatus());
    assertThat(fixture.sharingService.offer)
        .isEqualTo(
            ProfileShareOffer.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .targetHouseholdId(householdId)
                .build());
  }

  @Test
  @DisplayName("Should accept profile share with manager invitation")
  void shouldAcceptProfileShareWithManagerInvitation() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();
    var invitationId = UUID.randomUUID();

    var result =
        fixture.resolver.acceptProfileShare(
            new PortableProfileInputs.ShareAcceptance(shareId.toString(), invitationId.toString()));

    assertThat(result.profileId()).isEqualTo(fixture.sharingService.shareResult.getProfileId());
    assertThat(result.householdId()).isEqualTo(fixture.sharingService.shareResult.getHouseholdId());
    assertThat(result.status()).isEqualTo(fixture.sharingService.shareResult.getStatus());
    assertThat(fixture.sharingService.acceptance)
        .isEqualTo(
            ProfileShareAcceptance.builder()
                .authority(fixture.identity)
                .shareId(shareId)
                .managementInvitationId(invitationId)
                .build());
  }

  @Test
  @DisplayName("Should accept adult profile share without manager invitation")
  void shouldAcceptAdultProfileShareWithoutManagerInvitation() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();

    fixture.resolver.acceptProfileShare(
        new PortableProfileInputs.ShareAcceptance(shareId.toString(), null));

    assertThat(fixture.sharingService.acceptance.managementInvitationId()).isNull();
  }

  @Test
  @DisplayName("Should reject profile share as authenticated account")
  void shouldRejectProfileShareAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();

    assertThat(fixture.resolver.rejectProfileShare(shareId.toString())).isTrue();
    assertThat(fixture.sharingService.rejection)
        .isEqualTo(
            ProfileShareRejection.builder().authority(fixture.identity).shareId(shareId).build());
  }

  @Test
  @DisplayName("Should cancel profile share as authenticated account")
  void shouldCancelProfileShareAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();

    assertThat(fixture.resolver.cancelProfileShare(shareId.toString())).isTrue();
    assertThat(fixture.sharingService.cancellation)
        .isEqualTo(
            ProfileShareCancellation.builder()
                .actingAccountId(fixture.accountId)
                .shareId(shareId)
                .build());
  }

  @Test
  @DisplayName("Should remove profile from authenticated account household")
  void shouldRemoveProfileFromAuthenticatedAccountHousehold() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();

    assertThat(fixture.resolver.removeProfileFromCurrentHousehold(shareId.toString())).isTrue();
    assertThat(fixture.sharingService.removal)
        .isEqualTo(
            HouseholdProfileRemoval.builder().authority(fixture.identity).shareId(shareId).build());
  }

  @Test
  @DisplayName("Should derive leave current home account and profile from authenticated token")
  void shouldDeriveLeaveCurrentHomeAccountAndProfileFromAuthenticatedToken() {
    var fixture = new ResolverFixture();

    assertThat(fixture.resolver.leaveCurrentHome()).isTrue();
    assertThat(fixture.sharingService.departure)
        .isEqualTo(ProfileHomeDeparture.builder().authority(fixture.identity).build());
  }

  @Test
  @DisplayName("Should invite profile manager as authenticated account")
  void shouldInviteProfileManagerAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();
    var invitedAccountId = UUID.randomUUID();

    var result =
        fixture.resolver.inviteProfileManager(
            new PortableProfileInputs.ManagerInvite(
                profileId.toString(), invitedAccountId.toString()));

    assertThat(result.profileId())
        .isEqualTo(fixture.managementService.invitationResult.getProfileId());
    assertThat(result.invitedAccountId())
        .isEqualTo(fixture.managementService.invitationResult.getInvitedAccountId());
    assertThat(result.status()).isEqualTo(fixture.managementService.invitationResult.getStatus());
    assertThat(fixture.managementService.invite)
        .isEqualTo(
            ProfileManagerInvite.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .invitedAccountId(invitedAccountId)
                .build());
  }

  @Test
  @DisplayName("Should accept profile manager invitation as authenticated account")
  void shouldAcceptProfileManagerInvitationAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var invitationId = UUID.randomUUID();

    var result =
        fixture.resolver.acceptProfileManagerInvitation(
            new PortableProfileInputs.InvitationAcceptance(invitationId.toString()));

    assertThat(result.profileId())
        .isEqualTo(fixture.managementService.managerResult.getProfileId());
    assertThat(result.accountId())
        .isEqualTo(fixture.managementService.managerResult.getAccountId());
    assertThat(fixture.managementService.acceptance)
        .isEqualTo(
            ProfileManagerInvitationAcceptance.builder()
                .actingAccountId(fixture.accountId)
                .invitationId(invitationId)
                .build());
  }

  @Test
  @DisplayName("Should reject profile manager invitation as authenticated account")
  void shouldRejectProfileManagerInvitationAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var invitationId = UUID.randomUUID();

    assertThat(fixture.resolver.rejectProfileManagerInvitation(invitationId.toString())).isTrue();
    assertThat(fixture.managementService.rejection)
        .isEqualTo(
            ProfileManagerInvitationRejection.builder()
                .actingAccountId(fixture.accountId)
                .invitationId(invitationId)
                .build());
  }

  @Test
  @DisplayName("Should cancel profile manager invitation as authenticated account")
  void shouldCancelProfileManagerInvitationAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var invitationId = UUID.randomUUID();

    assertThat(fixture.resolver.cancelProfileManagerInvitation(invitationId.toString())).isTrue();
    assertThat(fixture.managementService.cancellation)
        .isEqualTo(
            ProfileManagerInvitationCancellation.builder()
                .actingAccountId(fixture.accountId)
                .invitationId(invitationId)
                .build());
  }

  @Test
  @DisplayName("Should relinquish profile management as authenticated account")
  void shouldRelinquishProfileManagementAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.relinquishProfileManagement(
                new PortableProfileInputs.ProfileReference(profileId.toString())))
        .isTrue();
    assertThat(fixture.managementService.relinquishment)
        .isEqualTo(
            ProfileManagementRelinquishment.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .build());
  }

  @Test
  @DisplayName("Should set portable profile kind as authenticated account")
  void shouldSetPortableProfileKindAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.setProfileKind(
                new PortableProfileInputs.ProfileKindChange(profileId.toString(), ProfileKind.KID)))
        .isTrue();
    assertThat(fixture.policyService.kindChange)
        .isEqualTo(
            SetProfileKindCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .kind(ProfileKind.KID)
                .build());
  }

  @Test
  @DisplayName("Should set portable profile content ceiling as authenticated account")
  void shouldSetPortableProfileContentCeilingAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.setProfileContentCeiling(
                new PortableProfileInputs.ProfileContentCeilingChange(profileId.toString(), 13)))
        .isTrue();
    assertThat(fixture.policyService.ceilingChange)
        .isEqualTo(
            SetProfileContentCeilingCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .maximumAllowedRatingAge(13)
                .build());
  }

  @Test
  @DisplayName("Should remove portable profile content ceiling as authenticated account")
  void shouldRemovePortableProfileContentCeilingAsAuthenticatedAccount() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.removeProfileContentCeiling(
                new PortableProfileInputs.ProfileReference(profileId.toString())))
        .isTrue();
    assertThat(fixture.policyService.ceilingRemoval)
        .isEqualTo(
            RemoveProfileContentCeilingCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .build());
  }

  @Test
  @DisplayName("Should reset portable profile PIN and hash it at GraphQL boundary")
  void shouldResetPortableProfilePinAndHashItAtGraphQlBoundary() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.resetProfilePin(
                new PortableProfileInputs.ProfilePinReset(profileId.toString(), "2468")))
        .isTrue();
    assertThat(fixture.policyService.pinReset)
        .isEqualTo(
            ResetProfilePinCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .pinHash("2468")
                .build());
  }

  @Test
  @DisplayName("Should reject blank PIN when resetting portable profile PIN")
  void shouldRejectBlankPinWhenResettingPortableProfilePin() {
    var fixture = new ResolverFixture();
    var input = new PortableProfileInputs.ProfilePinReset(UUID.randomUUID().toString(), "");

    assertThatThrownBy(() -> fixture.resolver.resetProfilePin(input))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should delete profile with fresh password")
  void shouldDeleteProfileWithFreshPassword() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.deleteProfile(
                new PortableProfileInputs.ProfileDeletion(profileId.toString(), "secret")))
        .isTrue();
    assertThat(fixture.deletionService.deletion())
        .isEqualTo(
            DeleteProfileCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .password("secret")
                .build());
  }

  @Test
  @DisplayName("Should force delete profile as server administrator")
  void shouldForceDeleteProfileAsServerAdministrator() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();

    assertThat(
            fixture.resolver.forceDeleteProfile(
                new PortableProfileInputs.ForceProfileDeletion(
                    profileId.toString(), "secret", "Recovery")))
        .isTrue();
    assertThat(fixture.serverAdministrationService.deletion())
        .isEqualTo(
            ForceProfileDeletionCommand.builder()
                .actingAccountId(fixture.accountId)
                .profileId(profileId)
                .password("secret")
                .reason("Recovery")
                .build());
  }

  @Test
  @DisplayName("Should force unshare profile as server administrator")
  void shouldForceUnshareProfileAsServerAdministrator() {
    var fixture = new ResolverFixture();
    var shareId = UUID.randomUUID();

    assertThat(
            fixture.resolver.forceUnshareProfile(
                new PortableProfileInputs.ForceProfileUnshare(
                    shareId.toString(), "secret", "Recovery")))
        .isTrue();
    assertThat(fixture.serverAdministrationService.unshare())
        .isEqualTo(
            ForceProfileUnshareCommand.builder()
                .actingAccountId(fixture.accountId)
                .shareId(shareId)
                .password("secret")
                .reason("Recovery")
                .build());
  }

  @Test
  @DisplayName("Should override profile manager as server administrator")
  void shouldOverrideProfileManagerAsServerAdministrator() {
    var fixture = new ResolverFixture();
    var profileId = UUID.randomUUID();
    var targetAccountId = UUID.randomUUID();

    assertThat(
            fixture.resolver.overrideProfileManager(
                new PortableProfileInputs.ManagerOverride(
                    profileId.toString(),
                    targetAccountId.toString(),
                    ProfileManagerOverrideAction.GRANT,
                    "secret",
                    "Recovery")))
        .isTrue();
    assertThat(fixture.serverAdministrationService.override())
        .isEqualTo(
            ProfileManagerOverrideCommand.builder()
                .actingAccountId(fixture.accountId)
                .targetAccountId(targetAccountId)
                .profileId(profileId)
                .action(ProfileManagerOverrideAction.GRANT)
                .password("secret")
                .reason("Recovery")
                .build());
  }

  @Test
  @DisplayName("Should transfer account household as server administrator")
  void shouldTransferAccountHouseholdAsServerAdministrator() {
    var fixture = new ResolverFixture();
    var targetAccountId = UUID.randomUUID();
    var targetHouseholdId = UUID.randomUUID();

    assertThat(
            fixture.resolver.transferAccountHousehold(
                new PortableProfileInputs.AccountTransfer(
                    targetAccountId.toString(),
                    targetHouseholdId.toString(),
                    HouseholdRole.PARENT,
                    "secret",
                    "Recovery")))
        .isTrue();
    assertThat(fixture.householdAdministrationService.accountTransfer())
        .isEqualTo(
            AccountHouseholdTransferCommand.builder()
                .actingAccountId(fixture.accountId)
                .targetAccountId(targetAccountId)
                .targetHouseholdId(targetHouseholdId)
                .targetRole(HouseholdRole.PARENT)
                .password("secret")
                .reason("Recovery")
                .build());
  }

  @Test
  @DisplayName("Should transfer household ownership as authenticated owner")
  void shouldTransferHouseholdOwnershipAsAuthenticatedOwner() {
    var fixture = new ResolverFixture();
    var householdId = UUID.randomUUID();
    var targetAccountId = UUID.randomUUID();

    assertThat(
            fixture.resolver.transferHouseholdOwnership(
                new PortableProfileInputs.OwnershipTransfer(
                    householdId.toString(), targetAccountId.toString(), "secret", "Family change")))
        .isTrue();
    assertThat(fixture.householdAdministrationService.ownershipTransfer())
        .isEqualTo(
            HouseholdOwnershipTransferCommand.builder()
                .authority(fixture.identity)
                .householdId(householdId)
                .targetAccountId(targetAccountId)
                .password("secret")
                .reason("Family change")
                .build());
  }

  private static final class ResolverFixture {

    private final UUID accountId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final AuthenticatedIdentity identity =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .profileId(profileId)
            .role(AccountRole.ADMIN)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.OWNER)
            .build();
    private final CapturingProfileSharingService sharingService =
        new CapturingProfileSharingService();
    private final CapturingProfileManagementService managementService =
        new CapturingProfileManagementService();
    private final CapturingProfilePolicyService policyService = new CapturingProfilePolicyService();
    private final CapturingProfileDeletionService deletionService =
        new CapturingProfileDeletionService();
    private final CapturingServerAdministrationService serverAdministrationService =
        new CapturingServerAdministrationService();
    private final CapturingHouseholdAdministrationService householdAdministrationService =
        new CapturingHouseholdAdministrationService();
    private final PortableIdentityService portableIdentityService =
        PortableIdentityService.builder()
            .transactionTemplate(new TransactionTemplate(new NoOpTransactionManager()))
            .sharingService(sharingService)
            .managementService(managementService)
            .policyService(policyService)
            .deletionService(deletionService)
            .serverAdministrationService(serverAdministrationService)
            .householdAdministrationService(householdAdministrationService)
            .build();
    private final PortableProfileResolver resolver =
        new PortableProfileResolver(
            new FakeAuthorizationService(identity),
            portableIdentityService,
            new ProfilePinService(NoOpPasswordEncoder.getInstance()));
  }

  private static final class CapturingProfileSharingService extends ProfileSharingService {

    private final ProfileHouseholdShare shareResult =
        ProfileHouseholdShare.builder()
            .profileId(UUID.randomUUID())
            .householdId(UUID.randomUUID())
            .status(ProfileShareStatus.PENDING)
            .build();
    private ProfileShareOffer offer;
    private ProfileShareAcceptance acceptance;
    private ProfileShareRejection rejection;
    private ProfileShareCancellation cancellation;
    private HouseholdProfileRemoval removal;
    private ProfileHomeDeparture departure;

    private CapturingProfileSharingService() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public ProfileHouseholdShare offer(ProfileShareOffer offer) {
      this.offer = offer;
      return shareResult;
    }

    @Override
    public ProfileHouseholdShare accept(ProfileShareAcceptance acceptance) {
      this.acceptance = acceptance;
      return shareResult;
    }

    @Override
    public void reject(ProfileShareRejection rejection) {
      this.rejection = rejection;
    }

    @Override
    public void cancel(ProfileShareCancellation cancellation) {
      this.cancellation = cancellation;
    }

    @Override
    public void removeFromHousehold(HouseholdProfileRemoval removal) {
      this.removal = removal;
    }

    @Override
    public void leaveCurrentHome(ProfileHomeDeparture departure) {
      this.departure = departure;
    }
  }

  private static final class CapturingProfileManagementService extends ProfileManagementService {

    private final ProfileManagerInvitation invitationResult =
        ProfileManagerInvitation.builder()
            .profileId(UUID.randomUUID())
            .invitedAccountId(UUID.randomUUID())
            .status(ProfileManagerInvitationStatus.PENDING)
            .build();
    private final ProfileManager managerResult =
        ProfileManager.builder().profileId(UUID.randomUUID()).accountId(UUID.randomUUID()).build();
    private CreatePortableProfileCommand creation;
    private RenamePortableProfileCommand rename;
    private ProfileManagerInvite invite;
    private ProfileManagerInvitationAcceptance acceptance;
    private ProfileManagerInvitationRejection rejection;
    private ProfileManagerInvitationCancellation cancellation;
    private ProfileManagementRelinquishment relinquishment;

    private CapturingProfileManagementService() {
      super(null, null, null, null, null, null, null, null);
    }

    @Override
    public Profile create(CreatePortableProfileCommand command) {
      creation = command;
      return Profile.builder()
          .name(command.name())
          .kind(command.kind())
          .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
          .pinHash(command.pinHash())
          .build();
    }

    @Override
    public void rename(RenamePortableProfileCommand command) {
      rename = command;
    }

    @Override
    public ProfileManagerInvitation invite(ProfileManagerInvite command) {
      invite = command;
      return invitationResult;
    }

    @Override
    public ProfileManager accept(ProfileManagerInvitationAcceptance command) {
      acceptance = command;
      return managerResult;
    }

    @Override
    public void reject(ProfileManagerInvitationRejection command) {
      rejection = command;
    }

    @Override
    public void cancel(ProfileManagerInvitationCancellation command) {
      cancellation = command;
    }

    @Override
    public void relinquish(ProfileManagementRelinquishment command) {
      relinquishment = command;
    }
  }

  private static final class CapturingProfilePolicyService extends ProfilePolicyService {

    private SetProfileKindCommand kindChange;
    private SetProfileContentCeilingCommand ceilingChange;
    private RemoveProfileContentCeilingCommand ceilingRemoval;
    private ResetProfilePinCommand pinReset;

    private CapturingProfilePolicyService() {
      super(null, null, null, null, null);
    }

    @Override
    public void setKind(SetProfileKindCommand command) {
      kindChange = command;
    }

    @Override
    public void setContentCeiling(SetProfileContentCeilingCommand command) {
      ceilingChange = command;
    }

    @Override
    public void removeContentCeiling(RemoveProfileContentCeilingCommand command) {
      ceilingRemoval = command;
    }

    @Override
    public void resetPin(ResetProfilePinCommand command) {
      pinReset = command;
    }
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      // Resolver delegation tests intentionally have no transactional resource to begin.
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      // Captured commands are committed synchronously by the in-memory fake adapters.
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      // Resolver delegation tests do not exercise rollback behavior.
    }
  }
}
