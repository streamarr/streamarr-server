package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Intent;

/**
 * Compiles a domain intent into the Cedar check and the allowed value. The switch is exhaustive
 * over the sealed {@link Intent}, so a new intent without a plan fails to compile.
 */
final class IntentPlanner {

  private IntentPlanner() {}

  // java:S6878: SelectProfile uses accessors, not a record pattern — the pattern's synthetic
  // deconstruction branch can never be missed and would break the 100% JaCoCo branch gate.
  @SuppressWarnings({"unchecked", "java:S6878"})
  static <T> IntentPlan<T> plan(AuthenticatedIdentity identity, Intent<T> intent) {
    var check =
        switch (intent) {
          case Intent.AddLibrary _ -> AuthorizationCheck.onServer(Action.ADD_LIBRARY);
          case Intent.RemoveLibrary _ -> AuthorizationCheck.onServer(Action.REMOVE_LIBRARY);
          case Intent.ScanLibrary _ -> AuthorizationCheck.onServer(Action.SCAN_LIBRARY);
          case Intent.RefreshLibrary _ -> AuthorizationCheck.onServer(Action.REFRESH_LIBRARY);
          case Intent.ViewProfilePicker _ ->
              AuthorizationCheck.onHousehold(
                  Action.VIEW_PROFILE_PICKER, identity.contextHouseholdId());
          case Intent.SelectProfile select ->
              AuthorizationCheck.selectProfile(select.profileId(), select.pinVerified());
          case Intent.Playback _ ->
              AuthorizationCheck.onProfile(
                  Action.PLAYBACK, identity.playbackAuthority().profileId());
          case Intent.ViewProfileActivity(var profileId) ->
              AuthorizationCheck.onProfile(Action.VIEW_PROFILE_ACTIVITY, profileId);
          case Intent.ViewHouseholdAdministration(var householdId) ->
              AuthorizationCheck.onHousehold(Action.VIEW_HOUSEHOLD_ADMINISTRATION, householdId);
          case Intent.ViewHouseholds _ -> AuthorizationCheck.onServer(Action.VIEW_HOUSEHOLDS);
          case Intent.ViewAccountAdministration(var accountId) ->
              AuthorizationCheck.onAccount(Action.VIEW_ACCOUNT_ADMINISTRATION, accountId);
          case Intent.ViewProfileAdministration(var profileId) ->
              AuthorizationCheck.onProfile(Action.VIEW_PROFILE_ADMINISTRATION, profileId);
          case Intent.GrantServerAdmin(var accountId) ->
              AuthorizationCheck.onAccount(Action.GRANT_SERVER_ADMIN, accountId);
          case Intent.RevokeServerAdmin(var accountId) ->
              AuthorizationCheck.onAccount(Action.REVOKE_SERVER_ADMIN, accountId);
          case Intent.CreateHousehold _ -> AuthorizationCheck.onServer(Action.CREATE_HOUSEHOLD);
          case Intent.RenameHousehold(var householdId) ->
              AuthorizationCheck.onHousehold(Action.RENAME_HOUSEHOLD, householdId);
          case Intent.RenameAccount(var accountId) ->
              AuthorizationCheck.onAccount(Action.RENAME_ACCOUNT, accountId);
          case Intent.GrantHouseholdAdmin(var accountId) ->
              AuthorizationCheck.onAccount(Action.GRANT_HOUSEHOLD_ADMIN, accountId);
          case Intent.RevokeHouseholdAdmin(var accountId) ->
              AuthorizationCheck.onAccount(Action.REVOKE_HOUSEHOLD_ADMIN, accountId);
          case Intent.DisableAccount(var accountId) ->
              AuthorizationCheck.onAccount(Action.DISABLE_ACCOUNT, accountId);
          case Intent.EnableAccount(var accountId) ->
              AuthorizationCheck.onAccount(Action.ENABLE_ACCOUNT, accountId);
          case Intent.CreateProfile(var householdId) ->
              AuthorizationCheck.onHousehold(Action.CREATE_PROFILE, householdId);
          case Intent.RenameProfile(var profileId) ->
              AuthorizationCheck.onProfile(Action.EDIT_PROFILE, profileId);
          case Intent.SetProfilePicture(var profileId) ->
              AuthorizationCheck.onProfile(Action.EDIT_PROFILE, profileId);
          case Intent.ManageProfilePin(var profileId) ->
              AuthorizationCheck.onProfile(Action.MANAGE_PROFILE_PIN, profileId);
          case Intent.OverrideProfilePin(var profileId) ->
              AuthorizationCheck.onProfile(Action.OVERRIDE_PROFILE_PIN, profileId);
          case Intent.DeleteProfile(var profileId) ->
              AuthorizationCheck.onProfile(Action.DELETE_PROFILE, profileId);
          case Intent.ProfilePolicyChange change ->
              throw new IllegalStateException(
                  "Policy changes are planned with their transition: " + change.getClass());
        };
    return (IntentPlan<T>) new IntentPlan<>(check, AuthorizationUnit.INSTANCE);
  }
}
