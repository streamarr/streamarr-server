package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.model.Context;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import java.util.Map;
import java.util.UUID;

/** One Cedar question: action, resource, and the trusted attempt-specific context. */
record AuthorizationCheck(Action action, EntityUID resource, Context context) {

  static AuthorizationCheck onServer(Action action) {
    return new AuthorizationCheck(action, CedarIds.server(), new Context());
  }

  static AuthorizationCheck onHousehold(Action action, UUID householdId) {
    return new AuthorizationCheck(action, CedarIds.household(householdId), new Context());
  }

  static AuthorizationCheck onAccount(Action action, UUID accountId) {
    return new AuthorizationCheck(action, CedarIds.account(accountId), new Context());
  }

  static AuthorizationCheck onProfile(Action action, UUID profileId) {
    return new AuthorizationCheck(action, CedarIds.profile(profileId), new Context());
  }

  static AuthorizationCheck selectProfile(UUID profileId, boolean pinVerified) {
    return new AuthorizationCheck(
        Action.SELECT_PROFILE,
        CedarIds.profile(profileId),
        new Context(Map.of("pinVerified", new PrimBool(pinVerified))));
  }

  /** The resource's opaque id, for contributors that load resource facts. */
  UUID resourceId() {
    return CedarIds.idOf(resource);
  }
}
