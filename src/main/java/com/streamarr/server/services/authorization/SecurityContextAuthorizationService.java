package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class SecurityContextAuthorizationService implements AuthorizationService {

  @Override
  public AuthenticatedIdentity currentIdentity() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return token.getPrincipal();
    }
    throw new AuthenticationRequiredException();
  }

  @Override
  public UUID requireAccountId() {
    return currentIdentity().accountId();
  }

  @Override
  public UUID requireHousehold() {
    var householdId = currentIdentity().householdId();
    if (householdId == null) {
      throw new HouseholdRequiredException();
    }
    return householdId;
  }

  @Override
  public UUID requireProfile() {
    var profileId = currentIdentity().profileId();
    if (profileId == null) {
      throw new ProfileRequiredException();
    }
    return profileId;
  }

  @Override
  public boolean isServerAdmin() {
    return currentIdentity().role() == AccountRole.ADMIN;
  }

  @Override
  public void requireServerAdmin() {
    if (!isServerAdmin()) {
      throw new AccessDeniedException("Server administrator role is required.");
    }
  }

  @Override
  public void requireHouseholdRole(HouseholdRole minimum) {
    var identity = currentIdentity();
    if (identity.householdRole() == null) {
      throw new HouseholdRequiredException();
    }
    if (rank(identity.householdRole()) < rank(minimum)) {
      throw new AccessDeniedException("Household role " + minimum + " or higher is required.");
    }
  }

  @Override
  public boolean canViewActivityOf(UUID profileId) {
    var identity = currentIdentity();
    if (profileId.equals(identity.profileId())) {
      return true;
    }
    return identity.householdRole() != null
        && rank(identity.householdRole()) >= rank(HouseholdRole.PARENT);
  }

  private static int rank(HouseholdRole role) {
    return switch (role) {
      case MEMBER -> 0;
      case PARENT -> 1;
      case OWNER -> 2;
    };
  }
}
