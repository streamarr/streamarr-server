package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.exceptions.InvalidIdException;
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
import com.streamarr.server.services.auth.PortableIdentityTransactionExecutor;
import com.streamarr.server.services.auth.ProfileDeletionService;
import com.streamarr.server.services.auth.ProfileHomeDeparture;
import com.streamarr.server.services.auth.ProfileManagementRelinquishment;
import com.streamarr.server.services.auth.ProfileManagementService;
import com.streamarr.server.services.auth.ProfileManagerInvitationAcceptance;
import com.streamarr.server.services.auth.ProfileManagerInvitationCancellation;
import com.streamarr.server.services.auth.ProfileManagerInvitationRejection;
import com.streamarr.server.services.auth.ProfileManagerInvite;
import com.streamarr.server.services.auth.ProfileManagerOverrideCommand;
import com.streamarr.server.services.auth.ProfilePolicyChange;
import com.streamarr.server.services.auth.ProfilePolicyService;
import com.streamarr.server.services.auth.ProfileShareAcceptance;
import com.streamarr.server.services.auth.ProfileShareCancellation;
import com.streamarr.server.services.auth.ProfileShareOffer;
import com.streamarr.server.services.auth.ProfileShareRejection;
import com.streamarr.server.services.auth.ProfileSharingService;
import com.streamarr.server.services.auth.RenamePortableProfileCommand;
import com.streamarr.server.services.auth.ServerAdministrationService;
import com.streamarr.server.services.authorization.AuthorizationService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@DgsComponent
@RequiredArgsConstructor
public class PortableProfileResolver {

  private final AuthorizationService authorizationService;
  private final PortableIdentityTransactionExecutor transactionExecutor;
  private final ProfileSharingService sharingService;
  private final ProfileManagementService managementService;
  private final ProfilePolicyService policyService;
  private final ProfileDeletionService deletionService;
  private final ServerAdministrationService serverAdministrationService;
  private final HouseholdAdministrationService householdAdministrationService;
  private final PasswordEncoder passwordEncoder;

  @DgsMutation
  public PortableProfileSummary createPortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileCreation input) {
    var accountId = authorizationService.requireAccountId();
    return transactionExecutor.execute(
        () -> {
          var profile =
              managementService.create(
                  CreatePortableProfileCommand.builder()
                      .actingAccountId(accountId)
                      .name(input.name())
                      .classification(input.classification())
                      .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                      .pinHash(input.pin() == null ? null : passwordEncoder.encode(input.pin()))
                      .build());
          return new PortableProfileSummary(
              profile.getId(),
              profile.getName(),
              profile.getClassification(),
              profile.getMaximumAllowedRatingAge(),
              profile.getPinHash() != null && !profile.getPinHash().isBlank());
        });
  }

  @DgsMutation
  public boolean renamePortableProfile(
      @InputArgument("input") PortableProfileInputs.ProfileRename input) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            managementService.rename(
                RenamePortableProfileCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .name(input.name())
                    .build()));
    return true;
  }

  @DgsMutation
  public ProfileHouseholdShare offerProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareOffer input) {
    var accountId = authorizationService.requireAccountId();
    return transactionExecutor.execute(
        () ->
            sharingService.offer(
                ProfileShareOffer.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .targetHouseholdId(parseUuid(input.targetHouseholdId()))
                    .build()));
  }

  @DgsMutation
  public ProfileHouseholdShare acceptProfileShare(
      @InputArgument("input") PortableProfileInputs.ShareAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    return transactionExecutor.execute(
        () ->
            sharingService.accept(
                ProfileShareAcceptance.builder()
                    .actingAccountId(accountId)
                    .shareId(parseUuid(input.shareId()))
                    .managementInvitationId(parseOptionalUuid(input.managementInvitationId()))
                    .build()));
  }

  @DgsMutation
  public boolean rejectProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            sharingService.reject(
                ProfileShareRejection.builder()
                    .actingAccountId(accountId)
                    .shareId(parseUuid(shareId))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean cancelProfileShare(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            sharingService.cancel(
                ProfileShareCancellation.builder()
                    .actingAccountId(accountId)
                    .shareId(parseUuid(shareId))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean removeProfileFromCurrentHousehold(@InputArgument String shareId) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            sharingService.removeFromHousehold(
                HouseholdProfileRemoval.builder()
                    .actingAccountId(accountId)
                    .shareId(parseUuid(shareId))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean leaveCurrentHome() {
    var accountId = authorizationService.requireAccountId();
    var profileId = authorizationService.requireProfile();
    transactionExecutor.execute(
        () ->
            sharingService.leaveCurrentHome(
                ProfileHomeDeparture.builder()
                    .actingAccountId(accountId)
                    .activeProfileId(profileId)
                    .build()));
    return true;
  }

  @DgsMutation
  public ProfileManagerInvitation inviteProfileManager(
      @InputArgument("input") PortableProfileInputs.ManagerInvite input) {
    var accountId = authorizationService.requireAccountId();
    return transactionExecutor.execute(
        () ->
            managementService.invite(
                ProfileManagerInvite.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .invitedAccountId(parseUuid(input.invitedAccountId()))
                    .build()));
  }

  @DgsMutation
  public ProfileManager acceptProfileManagerInvitation(
      @InputArgument("input") PortableProfileInputs.InvitationAcceptance input) {
    var accountId = authorizationService.requireAccountId();
    return transactionExecutor.execute(
        () ->
            managementService.accept(
                ProfileManagerInvitationAcceptance.builder()
                    .actingAccountId(accountId)
                    .invitationId(parseUuid(input.invitationId()))
                    .build()));
  }

  @DgsMutation
  public boolean rejectProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            managementService.reject(
                ProfileManagerInvitationRejection.builder()
                    .actingAccountId(accountId)
                    .invitationId(parseUuid(invitationId))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean cancelProfileManagerInvitation(@InputArgument String invitationId) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            managementService.cancel(
                ProfileManagerInvitationCancellation.builder()
                    .actingAccountId(accountId)
                    .invitationId(parseUuid(invitationId))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean relinquishProfileManagement(
      @InputArgument("input") PortableProfileInputs.ProfileReference input) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            managementService.relinquish(
                ProfileManagementRelinquishment.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean changeProfilePolicy(
      @InputArgument("input") PortableProfileInputs.PolicyChange input) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            policyService.changePolicy(
                ProfilePolicyChange.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .classification(input.classification())
                    .maximumAllowedRatingAge(input.maximumAllowedRatingAge())
                    .pinHash(input.pin() == null ? null : passwordEncoder.encode(input.pin()))
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean deleteProfile(
      @InputArgument("input") PortableProfileInputs.ProfileDeletion input) {
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            deletionService.delete(
                DeleteProfileCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
                    .password(input.password())
                    .build()));
    return true;
  }

  @DgsMutation
  public boolean forceDeleteProfile(
      @InputArgument("input") PortableProfileInputs.ForceProfileDeletion input) {
    authorizationService.requireServerAdmin();
    var accountId = authorizationService.requireAccountId();
    transactionExecutor.execute(
        () ->
            serverAdministrationService.forceDeleteProfile(
                ForceProfileDeletionCommand.builder()
                    .actingAccountId(accountId)
                    .profileId(parseUuid(input.profileId()))
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
    transactionExecutor.execute(
        () ->
            serverAdministrationService.forceUnshareProfile(
                ForceProfileUnshareCommand.builder()
                    .actingAccountId(accountId)
                    .shareId(parseUuid(input.shareId()))
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
    transactionExecutor.execute(
        () ->
            serverAdministrationService.overrideProfileManager(
                ProfileManagerOverrideCommand.builder()
                    .actingAccountId(accountId)
                    .targetAccountId(parseUuid(input.targetAccountId()))
                    .profileId(parseUuid(input.profileId()))
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
    transactionExecutor.execute(
        () ->
            householdAdministrationService.transferAccount(
                AccountHouseholdTransferCommand.builder()
                    .actingAccountId(accountId)
                    .targetAccountId(parseUuid(input.targetAccountId()))
                    .targetHouseholdId(parseUuid(input.targetHouseholdId()))
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
    transactionExecutor.execute(
        () ->
            householdAdministrationService.transferOwnership(
                HouseholdOwnershipTransferCommand.builder()
                    .actingAccountId(accountId)
                    .householdId(parseUuid(input.householdId()))
                    .targetAccountId(parseUuid(input.targetAccountId()))
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
