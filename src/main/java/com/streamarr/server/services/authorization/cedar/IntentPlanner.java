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
          case Intent.ViewAccountAdministration(var accountId) ->
              AuthorizationCheck.onAccount(Action.VIEW_ACCOUNT_ADMINISTRATION, accountId);
          case Intent.ViewProfileAdministration(var profileId) ->
              AuthorizationCheck.onProfile(Action.VIEW_PROFILE_ADMINISTRATION, profileId);
        };
    return (IntentPlan<T>) new IntentPlan<>(check, AuthorizationUnit.INSTANCE);
  }
}
