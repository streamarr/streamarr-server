package com.streamarr.server.services.authorization;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates one intent for one identity. Implemented by the Cedar engine package and known only to
 * {@link SecurityContextAuthorizationService}; everything else goes through {@link
 * AuthorizationService}.
 */
public interface AuthorizationDecider {

  Decision<AuthorizationUnit> decide(AuthenticatedIdentity identity, Intent.UnitIntent intent);

  Decision<ProfilePolicyTransition> decide(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent);

  /**
   * Decides each read intent as {@link #decide} decides it alone, reading each fact family once for
   * all of them. A write among them fails the whole batch closed: a write locks its facts.
   */
  Map<Intent.UnitIntent, Decision<AuthorizationUnit>> decideEach(
      AuthenticatedIdentity identity, Set<Intent.UnitIntent> intents);
}
