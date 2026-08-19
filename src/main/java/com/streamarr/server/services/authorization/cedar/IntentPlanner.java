package com.streamarr.server.services.authorization.cedar;

import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Intent;

/**
 * Compiles a domain intent into the Cedar check and the allowed value. The switch is exhaustive
 * over the sealed {@link Intent}, so a new intent without a plan fails to compile.
 */
final class IntentPlanner {

  private IntentPlanner() {}

  @SuppressWarnings("unchecked")
  static <T> IntentPlan<T> plan(Intent<T> intent) {
    var plan =
        switch (intent) {
          case Intent.AddLibrary _ -> unitOnServer(Action.ADD_LIBRARY);
          case Intent.RemoveLibrary _ -> unitOnServer(Action.REMOVE_LIBRARY);
          case Intent.ScanLibrary _ -> unitOnServer(Action.SCAN_LIBRARY);
          case Intent.RefreshLibrary _ -> unitOnServer(Action.REFRESH_LIBRARY);
        };
    return (IntentPlan<T>) plan;
  }

  private static IntentPlan<AuthorizationUnit> unitOnServer(Action action) {
    return new IntentPlan<>(AuthorizationCheck.onServer(action), AuthorizationUnit.INSTANCE);
  }
}
