package com.streamarr.server.services.authorization;

import com.streamarr.server.services.auth.AuthenticatedIdentity;

/**
 * Evaluates one intent for one identity. Implemented by the Cedar engine package and known only to
 * {@link SecurityContextAuthorizationService}; everything else goes through {@link
 * AuthorizationService}.
 */
public interface AuthorizationDecider {

  <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent);
}
