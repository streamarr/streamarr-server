package com.streamarr.server.config.security;

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
        """
        SCOPE_PROFILE > SCOPE_HOUSEHOLD
        SCOPE_HOUSEHOLD > SCOPE_ACCOUNT
        """);
  }
}
