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
import com.streamarr.server.services.auth.HouseholdOwnershipTransferCommand;
import com.streamarr.server.services.auth.HouseholdProfileRemoval;
import com.streamarr.server.services.auth.PortableIdentityService;
import com.streamarr.server.services.auth.ProfileHomeDeparture;
import com.streamarr.server.services.auth.ProfileManagementRelinquishment;
import com.streamarr.server.services.auth.ProfileManagerInvitationAcceptance;
import com.streamarr.server.services.auth.ProfileManagerInvitationCancellation;
import com.streamarr.server.services.auth.ProfileManagerInvitationRejection;
import com.streamarr.server.services.auth.ProfileManagerInvite;
import com.streamarr.server.services.auth.ProfileManagerOverrideCommand;
import com.streamarr.server.services.auth.ProfilePinService;
import com.streamarr.server.services.auth.ProfileShareAcceptance;
import com.streamarr.server.services.auth.ProfileShareCancellation;
import com.streamarr.server.services.auth.ProfileShareOffer;
import com.streamarr.server.services.auth.ProfileShareRejection;
import com.streamarr.server.services.auth.RemoveProfileContentCeilingCommand;
import com.streamarr.server.services.auth.RenamePortableProfileCommand;
import com.streamarr.server.services.auth.ResetProfilePinCommand;
import com.streamarr.server.services.auth.SetProfileContentCeilingCommand;
import com.streamarr.server.services.auth.SetProfileKindCommand;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class PortableProfileResolver {

  private final AuthorizationService authorizationService;
  private final PortableIdentityService portableIdentityService;
  private final ProfilePinService profilePinService;

  /**
   * Creates a portable profile for the authenticated account.
   *
   * @param input the profile name, kind, content-rating limit, and optional PIN
   * @return a summary of the newly created profile
   */
  @DgsMutation
  public PortableProfileSummary createPortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileCreation input) {
    var accountId = authorizationService.requireAccountId();
    var pinHash = input.pin() == null ? null : profilePinService.encode(input.pin());
    var profile =
        portableIdentityService.createPortableProfile(
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
  }

  /**
   * Renames a portable profile.
   *
   * @param input the profile identifier and new name
   * @return {@code true} when the profile is renamed successfully
   */
  @DgsMutation
  public boolean renamePortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileRename input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.renamePortableProfile(
        RenamePortableProfileCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .name(input.name())
            .build());
    return true;
  }

  /**
   * Offers a portable profile to another household.
   *
   * @param input the profile and target household identifiers for the share offer
   * @return a summary of the profile share offer
   */
  @DgsMutation
  public PortableProfileShareSummary offerProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareOffer input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var targetHouseholdId = parseUuid(input.targetHouseholdId());
    return PortableProfileShareSummary.from(
        portableIdentityService.offerProfileShare(
            ProfileShareOffer.builder()
                .actingAccountId(accountId)
                .profileId(profileId)
                .targetHouseholdId(targetHouseholdId)
                .build()));
  }

  /**
   * Accepts a portable profile share for the authenticated account.
   *
   * @param input the share identifier and optional management invitation identifier
   * @return a summary of the accepted profile share
   */
  @DgsMutation
  public PortableProfileShareSummary acceptProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    var shareId = parseUuid(input.shareId());
    var managementInvitationId = parseOptionalUuid(input.managementInvitationId());
    return PortableProfileShareSummary.from(
        portableIdentityService.acceptProfileShare(
            ProfileShareAcceptance.builder()
                .actingAccountId(accountId)
                .shareId(shareId)
                .managementInvitationId(managementInvitationId)
                .build()));
  }

  /**
   * Rejects an offered profile share.
   *
   * @param shareId the identifier of the profile share to reject
   * @return {@code true} when the share is rejected
   */
  @DgsMutation
  public boolean rejectProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    portableIdentityService.rejectProfileShare(
        ProfileShareRejection.builder().actingAccountId(accountId).shareId(parsedShareId).build());
    return true;
  }

  /**
   * Cancels a pending profile-sharing offer.
   *
   * @param shareId the identifier of the profile-sharing offer to cancel
   * @return {@code true} when the offer is cancelled
   */
  @DgsMutation
  public boolean cancelProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    portableIdentityService.cancelProfileShare(
        ProfileShareCancellation.builder()
            .actingAccountId(accountId)
            .shareId(parsedShareId)
            .build());
    return true;
  }

  /**
   * Removes a shared profile from its current household.
   *
   * @param shareId the identifier of the profile share to remove
   * @return {@code true} when the profile is removed
   */
  @DgsMutation
  public boolean removeProfileFromCurrentHousehold(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    var parsedShareId = parseUuid(shareId);
    portableIdentityService.removeProfileFromCurrentHousehold(
        HouseholdProfileRemoval.builder()
            .actingAccountId(accountId)
            .shareId(parsedShareId)
            .build());
    return true;
  }

  /**
   * Removes the authenticated active profile from its current household.
   *
   * @return {@code true} when the profile leaves its current household
   */
  @DgsMutation
  public boolean leaveCurrentHome() {
    var accountId = authorizationService.requireAccountId();
    var profileId = authorizationService.requireProfile();
    portableIdentityService.leaveCurrentHome(
        ProfileHomeDeparture.builder()
            .actingAccountId(accountId)
            .activeProfileId(profileId)
            .build());
    return true;
  }

  /**
   * Invites an account to manage a portable profile.
   *
   * @param input the profile and account identifiers for the management invitation
   * @return the created profile manager invitation summary
   */
  @DgsMutation
  public PortableProfileManagerInvitationSummary inviteProfileManager(
      @InputArgument("input") PortableProfileInputs.ManagerInvite input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var invitedAccountId = parseUuid(input.invitedAccountId());
    return PortableProfileManagerInvitationSummary.from(
        portableIdentityService.inviteProfileManager(
            ProfileManagerInvite.builder()
                .actingAccountId(accountId)
                .profileId(profileId)
                .invitedAccountId(invitedAccountId)
                .build()));
  }

  /**
   * Accepts an invitation to manage a portable profile.
   *
   * @param input the invitation identifier to accept
   * @return a summary of the resulting profile management relationship
   */
  @DgsMutation
  public PortableProfileManagerSummary acceptProfileManagerInvitation(
      @InputArgument("input") PortableProfileInputs.InvitationAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    var invitationId = parseUuid(input.invitationId());
    return PortableProfileManagerSummary.from(
        portableIdentityService.acceptProfileManagerInvitation(
            ProfileManagerInvitationAcceptance.builder()
                .actingAccountId(accountId)
                .invitationId(invitationId)
                .build()));
  }

  /**
   * Rejects an invitation to manage a profile.
   *
   * @param invitationId the identifier of the management invitation to reject
   * @return {@code true} when the invitation is rejected
   */
  @DgsMutation
  public boolean rejectProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    var parsedInvitationId = parseUuid(invitationId);
    portableIdentityService.rejectProfileManagerInvitation(
        ProfileManagerInvitationRejection.builder()
            .actingAccountId(accountId)
            .invitationId(parsedInvitationId)
            .build());
    return true;
  }

  /**
   * Cancels a profile management invitation.
   *
   * @param invitationId the identifier of the invitation to cancel
   * @return {@code true} when the invitation is cancelled
   */
  @DgsMutation
  public boolean cancelProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    var parsedInvitationId = parseUuid(invitationId);
    portableIdentityService.cancelProfileManagerInvitation(
        ProfileManagerInvitationCancellation.builder()
            .actingAccountId(accountId)
            .invitationId(parsedInvitationId)
            .build());
    return true;
  }

  /**
   * Ends the authenticated account's management of a profile.
   *
   * @param input identifies the profile whose management is relinquished
   * @return {@code true} when management is relinquished
   */
  @DgsMutation
  public boolean relinquishProfileManagement(
      @InputArgument("input") PortableProfileInputs.ProfileReference input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.relinquishProfileManagement(
        ProfileManagementRelinquishment.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .build());
    return true;
  }

  /**
   * Changes the kind of a portable profile.
   *
   * @param input the profile identifier and new profile kind
   * @return {@code true} when the profile kind is changed
   */
  @DgsMutation
  public boolean setProfileKind(
      @InputArgument("input") PortableProfileInputs.ProfileKindChange input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.setProfileKind(
        SetProfileKindCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .kind(input.kind())
            .build());
    return true;
  }

  /**
   * Sets the maximum content-rating age allowed for a portable profile.
   *
   * @param input the profile identifier and maximum allowed rating age
   * @return {@code true} if the content ceiling is set successfully
   */
  @DgsMutation
  public boolean setProfileContentCeiling(
      @InputArgument("input") PortableProfileInputs.ProfileContentCeilingChange input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.setProfileContentCeiling(
        SetProfileContentCeilingCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
            .build());
    return true;
  }

  /**
   * Removes the content-rating ceiling from a profile.
   *
   * @param input identifies the profile whose content-rating ceiling should be removed
   * @return {@code true} when the ceiling is removed
   */
  @DgsMutation
  public boolean removeProfileContentCeiling(
      @InputArgument("input") PortableProfileInputs.ProfileReference input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.removeProfileContentCeiling(
        RemoveProfileContentCeilingCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .build());
    return true;
  }

  /**
   * Resets the PIN for a portable profile.
   *
   * @param input the profile identifier and replacement PIN
   * @return {@code true} when the PIN is reset successfully
   */
  @DgsMutation
  public boolean resetProfilePin(
      @InputArgument("input") PortableProfileInputs.ProfilePinReset input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    var pinHash = profilePinService.encode(input.newPin());
    portableIdentityService.resetProfilePin(
        ResetProfilePinCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .pinHash(pinHash)
            .build());
    return true;
  }

  /**
   * Deletes a portable profile using the supplied password.
   *
   * @param input profile identifier and password required for deletion
   * @return {@code true} when the profile is deleted
   */
  @DgsMutation
  public boolean deleteProfile(
      @InputArgument("input") PortableProfileInputs.ProfileDeletion input) {
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.deleteProfile(
        DeleteProfileCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .password(input.password())
            .build());
    return true;
  }

  /**
   * Permanently deletes a profile with server-admin authorization.
   *
   * @param input the profile deletion details, including the profile identifier, password, and reason
   * @return {@code true} when the profile is deleted
   */
  @DgsMutation
  public boolean forceDeleteProfile(
      @InputArgument("input") PortableProfileInputs.ForceProfileDeletion input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var profileId = parseUuid(input.profileId());
    portableIdentityService.forceDeleteProfile(
        ForceProfileDeletionCommand.builder()
            .actingAccountId(accountId)
            .profileId(profileId)
            .password(input.password())
            .reason(input.reason())
            .build());
    return true;
  }

  /**
   * Forcefully removes a profile share.
   *
   * @param input the share identifier, password, and reason for the unsharing operation
   * @return {@code true} when the profile share is removed
   */
  @DgsMutation
  public boolean forceUnshareProfile(
      @InputArgument("input") PortableProfileInputs.ForceProfileUnshare input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var shareId = parseUuid(input.shareId());
    portableIdentityService.forceUnshareProfile(
        ForceProfileUnshareCommand.builder()
            .actingAccountId(accountId)
            .shareId(shareId)
            .password(input.password())
            .reason(input.reason())
            .build());
    return true;
  }

  /**
   * Overrides profile management for an account.
   *
   * @param input the profile manager override details, including the target account, profile, action, password, and reason
   * @return {@code true} when the override is completed
   */
  @DgsMutation
  public boolean overrideProfileManager(
      @InputArgument("input") PortableProfileInputs.ManagerOverride input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var targetAccountId = parseUuid(input.targetAccountId());
    var profileId = parseUuid(input.profileId());
    portableIdentityService.overrideProfileManager(
        ProfileManagerOverrideCommand.builder()
            .actingAccountId(accountId)
            .targetAccountId(targetAccountId)
            .profileId(profileId)
            .action(input.action())
            .password(input.password())
            .reason(input.reason())
            .build());
    return true;
  }

  /**
   * Transfers an account to a specified household with a designated role.
   *
   * @param input the target account, household, role, password, and transfer reason
   * @return {@code true} when the transfer succeeds
   */
  @DgsMutation
  public boolean transferAccountHousehold(
      @InputArgument("input") PortableProfileInputs.AccountTransfer input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    var targetAccountId = parseUuid(input.targetAccountId());
    var targetHouseholdId = parseUuid(input.targetHouseholdId());
    portableIdentityService.transferAccountHousehold(
        AccountHouseholdTransferCommand.builder()
            .actingAccountId(accountId)
            .targetAccountId(targetAccountId)
            .targetHouseholdId(targetHouseholdId)
            .targetRole(input.targetRole())
            .password(input.password())
            .reason(input.reason())
            .build());
    return true;
  }

  /**
   * Transfers ownership of a household to another account.
   *
   * @param input the household, target account, password, and reason for the transfer
   * @return {@code true} when ownership is transferred successfully
   */
  @DgsMutation
  public boolean transferHouseholdOwnership(
      @InputArgument("input") PortableProfileInputs.OwnershipTransfer input) {
    var accountId = authorizationService.requireAccountId();
    var householdId = parseUuid(input.householdId());
    var targetAccountId = parseUuid(input.targetAccountId());
    portableIdentityService.transferHouseholdOwnership(
        HouseholdOwnershipTransferCommand.builder()
            .actingAccountId(accountId)
            .householdId(householdId)
            .targetAccountId(targetAccountId)
            .password(input.password())
            .reason(input.reason())
            .build());
    return true;
  }

  /**
   * Converts a string identifier to a UUID.
   *
   * @param id the identifier to parse
   * @return the parsed UUID
   * @throws InvalidIdException if the identifier is not a valid UUID
   */
  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }

  /**
   * Parses an optional identifier into a UUID.
   *
   * @param id the identifier to parse, or {@code null}
   * @return the parsed UUID, or {@code null} when the identifier is {@code null}
   */
  private UUID parseOptionalUuid(String id) {
    return id == null ? null : parseUuid(id);
  }
}
