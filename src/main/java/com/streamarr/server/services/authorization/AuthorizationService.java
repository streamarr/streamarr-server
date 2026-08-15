package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Instant;
import java.util.UUID;

/**
 * The one place request identity is read for authorization decisions (ADR 0015); auditing reads the
 * SecurityContext separately for audit columns. Resolvers and controllers resolve identity through
 * this facade on the request thread and pass explicit ids down — services and batch loaders never
 * touch the SecurityContext.
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

  /**
 * Requires an authenticated household identity.
 *
 * @return the authenticated household identifier
 */
UUID requireHousehold();

  /**
 * Retrieves the authenticated profile identifier.
 *
 * @return the authenticated profile identifier
 */
UUID requireProfile();

  /**
 * Retrieves the authenticated request's playback authorization.
 *
 * @return the playback authorization for the authenticated requester
 */
PlaybackAuthority requirePlaybackAuthority();

  /**
 * Determines whether the authenticated requester has server-administrator privileges.
 *
 * @return {@code true} if the requester is a server administrator, {@code false} otherwise
 */
boolean isServerAdmin();

  /**
 * Requires the current requester to have server administrator privileges.
 */
void requireServerAdmin();

  /**
 * Ensures the authenticated requester has at least the specified household role.
 *
 * @param minimum the minimum household role required
 */
void requireHouseholdRole(HouseholdRole minimum);

  boolean canViewActivityOf(UUID profileId);
}
