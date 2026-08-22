package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Scope nesting (ADR 0024): a Profile-scoped token satisfies Account checks. There is no Household
 * scope — the context Household is a fact on every token. SCOPE_PLAYBACK is deliberately absent:
 * playback tokens authorize only stream paths and never inherit into (or from) the API scopes.
 */
public final class ScopeHierarchy {

  private ScopeHierarchy() {}

  public static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(grants(TokenScope.PROFILE, TokenScope.ACCOUNT));
  }

  private static String grants(TokenScope higher, TokenScope lower) {
    return higher.authority() + " > " + lower.authority();
  }
}
