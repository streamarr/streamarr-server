package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.graphql.dto.PortableProfileManagerInvitationSummary;
import com.streamarr.server.graphql.dto.PortableProfileManagerSummary;
import com.streamarr.server.graphql.dto.PortableProfileShareSummary;
import com.streamarr.server.graphql.dto.PortableProfileSummary;
import com.streamarr.server.graphql.inputs.PortableProfileInputs;
import com.streamarr.server.services.auth.AccountHouseholdTransferCommand;
import com.streamarr.server.services.auth.CreatePortableProfileCommand;
import com.streamarr.server.services.auth.DeleteProfileCommand;
import com.streamarr.server.services.auth.ForceProfileDeletionCommand;
import com.streamarr.server.services.auth.ForceProfileUnshareCommand;
import com.streamarr.server.services.auth.HouseholdAdministrationService;
import com.streamarr.server.services.auth.HouseholdOwnershipTransferCommand;
import com.streamarr.server.services.auth.HouseholdProfileRemoval;
import com.streamarr.server.services.auth.PortableIdentityMutationService;
import com.streamarr.server.services.auth.ProfileDeletionService;
import com.streamarr.server.services.auth.ProfileHomeDeparture;
import com.streamarr.server.services.auth.ProfileManagementRelinquishment;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfileManagerInvitationAcceptance;
import com.streamarr.server.services.auth.ProfileManagerInvitationCancellation;
import com.streamarr.server.services.auth.ProfileManagerInvitationRejection;
import com.streamarr.server.services.auth.ProfileManagerInvite;
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
import com.streamarr.server.services.auth.ServerAdministrationService;
import com.streamarr.server.services.auth.SetProfileContentCeilingCommand;
import com.streamarr.server.services.auth.SetProfileKindCommand;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class PortableProfileResolver {

  private final AuthorizationService authorizationService;
  private final PortableIdentityMutationService mutationService;
  private final ProfileSharingService sharingService;
  private final ProfileManagementService managementService;
  private final ProfilePolicyService policyService;
  private final ProfileDeletionService deletionService;
  private final ServerAdministrationService serverAdministrationService;
  private final HouseholdAdministrationService householdAdministrationService;
  private final ProfilePinService profilePinService;

  @DgsMutation
  public PortableProfileSummary createPortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileCreation input) {
    var accountId = authorizationService.requireAccountId();
    var pinHash = input.pin() == null ? null : profilePinService.encode(input.pin());
    return mutationService.execute(
        () -> {
          var profile =
              managementService.create(
                  CreatePortableProfileCommand.builder()
                      .actingAccountId(accountId)
                      .name(input.name())
                      .kind(input.kind())
                      .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                      .pinHash(pinHash)
                      .build());
          return new PortableProfileSummary(
              profile.getId(),
              profile.getName(),
              profile.getKind(),
              profile.getMaximumAllowedRatingAge(),
              profile.getPinHash() != null && !profile.getPinHash().isBlank());
        });
  }

  @DgsMutation
  public boolean renamePortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileRename input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            managementService.rename(
                RenamePortableProfileCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .name(input.name())
                    .build()));
    return true;
  }

  @DgsMutation
  public PortableProfileShareSummary offerProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareOffer input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var targetHouseholdId = parseUuid(input.targetHouseholdId());
    return PortableProfileShareSummary.from(
        mutationService.execute(
            () ->
                sharingService.offer(
                    ProfileShareOffer.builder()
                        .actingAccountId(accountId)
                        .profileId(profileId)
                        .targetHouseholdId(targetHouseholdId)
                        .build())));
  }

  @DgsMutation
  public PortableProfileShareSummary acceptProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    var shareId = parseUuid(input.shareId());
    var managementInvitationId = parseOptionalUuid(input.managementInvitationId());
    return PortableProfileShareSummary.from(
        mutationService.execute(
            () ->
                sharingService.accept(
                    ProfileShareAcceptance.builder()
                        .actingAccountId(accountId)
                        .shareId(shareId)
                        .managementInvitationId(managementInvitationId)
                        .build())));
  }

  @DgsMutation
  public boolean rejectProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    mutationService.execute(
        () ->
            sharingService.reject(
                ProfileShareRejection.builder()
                    .actingAccountId(accountId)
                    .shareId(parsedShareId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean cancelProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    mutationService.execute(
        () ->
            sharingService.cancel(
                ProfileShareCancellation.builder()
                    .actingAccountId(accountId)
                    .shareId(parsedShareId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean removeProfileFromCurrentHousehold(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    mutationService.execute(
        () ->
            sharingService.removeFromHousehold(
                HouseholdProfileRemoval.builder()
                    .actingAccountId(accountId)
                    .shareId(parsedShareId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean leaveCurrentHome() {
    var accountId = authorizationService.requireAccountId();
    var profileId = authorizationService.requireProfile();
    mutationService.execute(
        () ->
            sharingService.leaveCurrentHome(
                ProfileHomeDeparture.builder()
                    .actingAccountId(accountId)
                    .activeProfileId(profileId)
                    .build()));
    return true;
  }

  @DgsMutation
  public PortableProfileManagerInvitationSummary inviteProfileManager(
      @InputArgument("input") PortableProfileInputs.ManagerInvite input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var invitedAccountId = parseUuid(input.invitedAccountId());
    return PortableProfileManagerInvitationSummary.from(
        mutationService.execute(
            () ->
                managementService.invite(
                    ProfileManagerInvite.builder()
                        .actingAccountId(accountId)
                        .profileId(profileId)
                        .invitedAccountId(invitedAccountId)
                        .build())));
  }

  @DgsMutation
  public PortableProfileManagerSummary acceptProfileManagerInvitation(
      @InputArgument("input") PortableProfileInputs.InvitationAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    var invitationId = parseUuid(input.invitationId());
    return PortableProfileManagerSummary.from(
        mutationService.execute(
            () ->
                managementService.accept(
                    ProfileManagerInvitationAcceptance.builder()
                        .actingAccountId(accountId)
                        .invitationId(invitationId)
                        .build())));
  }

  @DgsMutation
  public boolean rejectProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    var parsedInvitationId = parseUuid(invitationId);
    mutationService.execute(
        () ->
            managementService.reject(
                ProfileManagerInvitationRejection.builder()
                    .actingAccountId(accountId)
                    .invitationId(parsedInvitationId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean cancelProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    var parsedInvitationId = parseUuid(invitationId);
    mutationService.execute(
        () ->
            managementService.cancel(
                ProfileManagerInvitationCancellation.builder()
                    .actingAccountId(accountId)
                    .invitationId(parsedInvitationId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean relinquishProfileManagement(
      @InputArgument("input") PortableProfileInputs.ProfileReference input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            managementService.relinquish(
                ProfileManagementRelinquishment.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean setProfileKind(
      @InputArgument("input") PortableProfileInputs.ProfileKindChange input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            policyService.setKind(
                SetProfileKindCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .kind(input.kind())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean setProfileContentCeiling(
      @InputArgument("input") PortableProfileInputs.ProfileContentCeilingChange input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            policyService.setContentCeiling(
                SetProfileContentCeilingCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean removeProfileContentCeiling(
      @InputArgument("input") PortableProfileInputs.ProfileReference input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            policyService.removeContentCeiling(
                RemoveProfileContentCeilingCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean resetProfilePin(
      @InputArgument("input") PortableProfileInputs.ProfilePinReset input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var pinHash = profilePinService.encode(input.newPin());
    mutationService.execute(
        () ->
            policyService.resetPin(
                ResetProfilePinCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .pinHash(pinHash)
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean deleteProfile(
      @InputArgument("input") PortableProfileInputs.ProfileDeletion input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            deletionService.delete(
                DeleteProfileCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .password(input.password())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean forceDeleteProfile(
      @InputArgument("input") PortableProfileInputs.ForceProfileDeletion input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            serverAdministrationService.forceDeleteProfile(
                ForceProfileDeletionCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(profileId)
                    .password(input.password())
                    .reason(input.reason())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean forceUnshareProfile(
      @InputArgument("input") PortableProfileInputs.ForceProfileUnshare input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var shareId = parseUuid(input.shareId());
    mutationService.execute(
        () ->
            serverAdministrationService.forceUnshareProfile(
                ForceProfileUnshareCommand.builder()
                    .actingAccountId(accountId)
                    .shareId(shareId)
                    .password(input.password())
                    .reason(input.reason())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean overrideProfileManager(
      @InputArgument("input") PortableProfileInputs.ManagerOverride input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var targetAccountId = parseUuid(input.targetAccountId());
    var profileId = parseUuid(input.profileId());
    mutationService.execute(
        () ->
            serverAdministrationService.overrideProfileManager(
                ProfileManagerOverrideCommand.builder()
                    .actingAccountId(accountId)
                    .targetAccountId(targetAccountId)
                    .profileId(profileId)
                    .action(input.action())
                    .password(input.password())
                    .reason(input.reason())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean transferAccountHousehold(
      @InputArgument("input") PortableProfileInputs.AccountTransfer input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var targetAccountId = parseUuid(input.targetAccountId());
    var targetHouseholdId = parseUuid(input.targetHouseholdId());
    mutationService.execute(
        () ->
            householdAdministrationService.transferAccount(
                AccountHouseholdTransferCommand.builder()
                    .actingAccountId(accountId)
                    .targetAccountId(targetAccountId)
                    .targetHouseholdId(targetHouseholdId)
                    .targetRole(input.targetRole())
                    .password(input.password())
                    .reason(input.reason())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean transferHouseholdOwnership(
      @InputArgument("input") PortableProfileInputs.OwnershipTransfer input) {
    var accountId = authorizationService.requireAccountId();
    var householdId = parseUuid(input.householdId());
    var targetAccountId = parseUuid(input.targetAccountId());
    mutationService.execute(
        () ->
            householdAdministrationService.transferOwnership(
                HouseholdOwnershipTransferCommand.builder()
                    .actingAccountId(accountId)
                    .householdId(householdId)
                    .targetAccountId(targetAccountId)
                    .password(input.password())
                    .reason(input.reason())
                    .build()));
    return true;
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }

  private UUID parseOptionalUuid(String id) {
    return id == null ? null : parseUuid(id);
  }
}
