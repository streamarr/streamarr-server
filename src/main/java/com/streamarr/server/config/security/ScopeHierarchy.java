package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.TokenScope;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;

/**
 * Scope nesting per ADR 0022: a profile-scoped token satisfies account checks. SCOPE_PLAYBACK is
 * deliberately absent — playback tokens authorize only stream paths and never inherit into (or
 * from) the API scopes.
 */
public final class ScopeHierarchy {

  private ScopeHierarchy() {}

  /**
   * Builds the token-scope hierarchy in which profile-scoped tokens satisfy account-scope checks.
   *
   * @return the configured token-scope role hierarchy
   */
  public static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.fromHierarchy(grants(TokenScope.PROFILE, TokenScope.ACCOUNT));
  }

  /**
   * Formats a scope hierarchy relationship between two scopes.
   *
   * @param higher the scope that grants access
   * @param lower  the scope whose access is granted
   * @return the hierarchy relationship in authority-string format
   */
  private static String grants(TokenScope higher, TokenScope lower) {
    return higher.authority() + " > " + lower.authority();
  }
}
