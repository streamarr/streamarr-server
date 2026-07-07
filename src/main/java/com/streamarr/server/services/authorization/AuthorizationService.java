package com.streamarr.server.services.authorization;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;

/**
 * The one place request identity is read (ADR 0015). Resolvers and controllers resolve identity
 * through this facade on the request thread and pass explicit ids down — services and batch loaders
 * never touch the SecurityContext.
 */
public interface AuthorizationService {

  AuthenticatedIdentity currentIdentity();

  UUID requireAccountId();

  UUID requireHousehold();

  UUID requireProfile();

  boolean isServerAdmin();

  void requireServerAdmin();

  void requireHouseholdRole(HouseholdRole minimum);

  boolean canViewActivityOf(UUID profileId);
}
