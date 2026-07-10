package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Scope nesting per ADR 0016: a profile-scoped token satisfies household and account checks.
 * SCOPE_PLAYBACK is deliberately absent — playback tokens authorize only stream paths and never
 * inherit into (or from) the API scopes.
 */
public final class ScopeHierarchy {

  private ScopeHierarchy() {}

  public static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(
        grants(TokenScope.PROFILE, TokenScope.HOUSEHOLD)
            + "\n"
            + grants(TokenScope.HOUSEHOLD, TokenScope.ACCOUNT));
  }

  private static String grants(TokenScope higher, TokenScope lower) {
    return higher.authority() + " > " + lower.authority();
  }
}
