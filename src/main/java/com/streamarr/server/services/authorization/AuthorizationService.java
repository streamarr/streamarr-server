package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Instant;
import java.util.UUID;

/**
 * The one place request identity is read (ADR 0015). Resolvers and controllers resolve identity
 * through this facade on the request thread and pass explicit ids down — services and batch loaders
 * never touch the SecurityContext.
 */
public interface AuthorizationService {

  AuthenticatedIdentity currentIdentity();

  /**
   * The raw value of the signature-verified token that authenticated this request. Sourced from the
   * validated token, never from a request parameter, so callers that echo it (e.g. playlist segment
   * URLs) reflect only server-validated input.
   */
  String currentTokenValue();

  /**
   * Expiry of the signature-verified token that authenticated this request. Sourced from the
   * validated token, never reparsed from the raw value, so derived credentials (scope-change
   * tokens) cap their lifetime against trusted input. Never null: the strict expiry validator
   * rejects tokens without an exp claim before they authenticate.
   */
  Instant currentTokenExpiry();

  UUID requireAccountId();

  UUID requireHousehold();

  UUID requireProfile();

  boolean isServerAdmin();

  void requireServerAdmin();

  void requireHouseholdRole(HouseholdRole minimum);

  boolean canViewActivityOf(UUID profileId);
}
