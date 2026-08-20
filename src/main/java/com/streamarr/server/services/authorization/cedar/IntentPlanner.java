package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Compiles a domain intent into the Cedar check and the allowed value. The switch is exhaustive
 * over the sealed {@link Intent}, so a new intent without a plan fails to compile.
 */
@Component
@RequiredArgsConstructor
final class IntentPlanner {

  @NonNull private final ProfilePolicyPlanner profilePolicyPlanner;

  // java:S6878: SelectProfile uses accessors, not a record pattern — the pattern's synthetic
  // deconstruction branch can never be missed and would break the 100% JaCoCo branch gate.
  @SuppressWarnings("java:S6878")
  IntentPlan<AuthorizationUnit> plan(
      @NonNull AuthenticatedIdentity identity, @NonNull Intent.UnitIntent intent) {
    return switch (intent) {
      case Intent.AddLibrary _ -> unitPlan(AuthorizationCheck.onServer(Action.ADD_LIBRARY));
      case Intent.RemoveLibrary _ -> unitPlan(AuthorizationCheck.onServer(Action.REMOVE_LIBRARY));
      case Intent.ScanLibrary _ -> unitPlan(AuthorizationCheck.onServer(Action.SCAN_LIBRARY));
      case Intent.RefreshLibrary _ -> unitPlan(AuthorizationCheck.onServer(Action.REFRESH_LIBRARY));
      case Intent.ViewProfilePicker _ ->
          unitPlan(
              AuthorizationCheck.onHousehold(
                  Action.VIEW_PROFILE_PICKER, identity.contextHouseholdId()));
      case Intent.SelectProfile select ->
          unitPlan(AuthorizationCheck.selectProfile(select.profileId(), select.pinVerified()));
      case Intent.Playback _ ->
          unitPlan(
              AuthorizationCheck.onProfile(
                  Action.PLAYBACK, identity.playbackAuthority().profileId()));
      case Intent.ViewProfileActivity(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.VIEW_PROFILE_ACTIVITY, profileId));
      case Intent.ViewHouseholdAdministration(var householdId) ->
          unitPlan(
              AuthorizationCheck.onHousehold(Action.VIEW_HOUSEHOLD_ADMINISTRATION, householdId));
      case Intent.ViewHouseholds _ -> unitPlan(AuthorizationCheck.onServer(Action.VIEW_HOUSEHOLDS));
      case Intent.ViewAccountAdministration(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.VIEW_ACCOUNT_ADMINISTRATION, accountId));
      case Intent.ViewProfileAdministration(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.VIEW_PROFILE_ADMINISTRATION, profileId));
      case Intent.GrantServerAdmin(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.GRANT_SERVER_ADMIN, accountId));
      case Intent.RevokeServerAdmin(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.REVOKE_SERVER_ADMIN, accountId));
      case Intent.CreateHousehold _ ->
          unitPlan(AuthorizationCheck.onServer(Action.CREATE_HOUSEHOLD));
      case Intent.RenameHousehold(var householdId) ->
          unitPlan(AuthorizationCheck.onHousehold(Action.RENAME_HOUSEHOLD, householdId));
      case Intent.RenameAccount(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.RENAME_ACCOUNT, accountId));
      case Intent.GrantHouseholdAdmin(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.GRANT_HOUSEHOLD_ADMIN, accountId));
      case Intent.RevokeHouseholdAdmin(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.REVOKE_HOUSEHOLD_ADMIN, accountId));
      case Intent.DisableAccount(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.DISABLE_ACCOUNT, accountId));
      case Intent.EnableAccount(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.ENABLE_ACCOUNT, accountId));
      case Intent.CreateProfile(var householdId) ->
          unitPlan(AuthorizationCheck.onHousehold(Action.CREATE_PROFILE, householdId));
      case Intent.CreateProfileWithLocalManager(var householdId) ->
          unitPlan(
              AuthorizationCheck.onHousehold(
                  Action.CREATE_PROFILE_WITH_LOCAL_MANAGER, householdId));
      case Intent.RenameProfile(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.EDIT_PROFILE, profileId));
      case Intent.SetProfilePicture(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.EDIT_PROFILE, profileId));
      case Intent.ManageProfilePin(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.MANAGE_PROFILE_PIN, profileId));
      case Intent.AdministrativelyResetProfilePin(var profileId) ->
          unitPlan(
              AuthorizationCheck.onProfile(Action.ADMINISTRATIVELY_RESET_PROFILE_PIN, profileId));
      case Intent.DeleteProfile(var profileId) ->
          unitPlan(AuthorizationCheck.onProfile(Action.DELETE_PROFILE, profileId));
      case Intent.IssueAccountInvitation _ ->
          unitPlan(AuthorizationCheck.onServer(Action.ISSUE_ACCOUNT_INVITATION));
      case Intent.CancelAccountInvitation _ ->
          unitPlan(AuthorizationCheck.onServer(Action.CANCEL_ACCOUNT_INVITATION));
      case Intent.ViewAccountInvitations _ ->
          unitPlan(AuthorizationCheck.onServer(Action.VIEW_ACCOUNT_INVITATIONS));
      case Intent.IssuePasswordReset(var accountId) ->
          unitPlan(AuthorizationCheck.onAccount(Action.ISSUE_PASSWORD_RESET, accountId));
    };
  }

  IntentPlan<ProfilePolicyTransition> plan(@NonNull Intent.ProfilePolicyChange intent) {
    return profilePolicyPlanner.plan(intent);
  }

  private static IntentPlan<AuthorizationUnit> unitPlan(AuthorizationCheck check) {
    return new IntentPlan<>(check, AuthorizationUnit.INSTANCE);
  }
}
