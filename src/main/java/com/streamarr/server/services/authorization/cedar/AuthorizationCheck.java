package com.streamarr.server.services.authorization.cedar;

import com.cedarpolicy.model.Context;
import com.cedarpolicy.value.EntityUID;
import com.cedarpolicy.value.PrimBool;
import com.cedarpolicy.value.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One Cedar question: action, resource, and the trusted attempt-specific context. */
record AuthorizationCheck(Action action, EntityUID resource, Map<String, Value> context) {

  static final String FRESH_REAUTHENTICATION = "freshReauthentication";

  static AuthorizationCheck onServer(Action action) {
    return new AuthorizationCheck(action, CedarIds.server(), Map.of());
  }

  static AuthorizationCheck onHousehold(Action action, UUID householdId) {
    return new AuthorizationCheck(action, CedarIds.household(householdId), Map.of());
  }

  static AuthorizationCheck onAccount(Action action, UUID accountId) {
    return new AuthorizationCheck(action, CedarIds.account(accountId), Map.of());
  }

  static AuthorizationCheck onProfile(Action action, UUID profileId) {
    return new AuthorizationCheck(action, CedarIds.profile(profileId), Map.of());
  }

  static AuthorizationCheck onShare(Action action, UUID shareId) {
    return new AuthorizationCheck(action, CedarIds.share(shareId), Map.of());
  }

  static AuthorizationCheck onManagerInvitation(Action action, UUID invitationId) {
    return new AuthorizationCheck(action, CedarIds.managerInvitation(invitationId), Map.of());
  }

  static AuthorizationCheck onGrant(Action action, UUID grantId) {
    return new AuthorizationCheck(action, CedarIds.grant(grantId), Map.of());
  }

  static AuthorizationCheck onRegistration(Action action, UUID registrationId) {
    return new AuthorizationCheck(action, CedarIds.registration(registrationId), Map.of());
  }

  static AuthorizationCheck selectProfile(UUID profileId, boolean pinVerified) {
    return new AuthorizationCheck(
        Action.SELECT_PROFILE,
        CedarIds.profile(profileId),
        Map.of("pinVerified", new PrimBool(pinVerified)));
  }

  /**
   * The same check with the trusted freshness flag set — the decider stamps the real value, and
   * repeats a denied group evaluation with {@code true} to classify it (ADR 0025).
   */
  AuthorizationCheck withFreshReauthentication(boolean fresh) {
    var merged = new LinkedHashMap<>(context);
    merged.put(FRESH_REAUTHENTICATION, new PrimBool(fresh));
    return new AuthorizationCheck(action, resource, merged);
  }

  Context cedarContext() {
    return new Context(context);
  }

  /** The resource's opaque id, for contributors that load resource facts. */
  UUID resourceId() {
    return CedarIds.idOf(resource);
  }
}
