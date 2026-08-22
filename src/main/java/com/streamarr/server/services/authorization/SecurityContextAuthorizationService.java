package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SecurityContextAuthorizationService implements AuthorizationService {

  private final ProfileRepository profileRepository;
  private final AccountProfileRepository accountProfileRepository;
  private final AuthorizationDecider decider;

  @Override
  public AuthenticatedIdentity currentIdentity() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return token.getPrincipal();
    }
    throw new AuthenticationRequiredException();
  }

  @Override
  public String currentTokenValue() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token
        && token.getCredentials() instanceof Jwt jwt) {
      return jwt.getTokenValue();
    }
    throw new AuthenticationRequiredException();
  }

  @Override
  public Instant currentTokenExpiry() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token
        && token.getCredentials() instanceof Jwt jwt
        && jwt.getExpiresAt() != null) {
      return jwt.getExpiresAt();
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
  public <T> Decision<T> decide(AuthenticatedIdentity identity, Intent<T> intent) {
    return decider.decide(identity, intent);
  }

  @Override
  public <T> T requireAllowed(AuthenticatedIdentity identity, Intent<T> intent) {
    return switch (decide(identity, intent)) {
      case Decision.Allowed<T>(var value) -> value;
      case Decision.Denied<T> _ -> throw new AccessDeniedException("Not allowed.");
      case Decision.Failed<T> _ -> throw new AuthorizationUnavailableException();
    };
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

  /**
   * ADR 0015 activity visibility: a server admin sees all activity, an owner or parent sees the
   * profiles of their active household, and everyone else sees their own activity plus the profiles
   * granted to them.
   */
  @Override
  public boolean canViewActivityOf(UUID profileId) {
    var identity = currentIdentity();
    if (profileId == null) {
      return false;
    }
    if (identity.role() == AccountRole.ADMIN || profileId.equals(identity.profileId())) {
      return true;
    }
    if (identity.householdId() == null) {
      return false;
    }
    if (managesHouseholdProfiles(identity)) {
      return profileInHousehold(profileId, identity.householdId());
    }
    return profileGrantedTo(identity, profileId);
  }

  // A scoped identity always carries a role with its household (AuthenticatedIdentity invariant).
  private static boolean managesHouseholdProfiles(AuthenticatedIdentity identity) {
    return rank(identity.householdRole()) >= rank(HouseholdRole.PARENT);
  }

  private boolean profileInHousehold(UUID profileId, UUID householdId) {
    return profileRepository
        .findById(profileId)
        .map(profile -> householdId.equals(profile.getHouseholdId()))
        .orElse(false);
  }

  private boolean profileGrantedTo(AuthenticatedIdentity identity, UUID profileId) {
    return accountProfileRepository
        .findByAccountIdAndHouseholdIdAndProfileId(
            identity.accountId(), identity.householdId(), profileId)
        .isPresent();
  }

  private static int rank(HouseholdRole role) {
    return switch (role) {
      case MEMBER -> 0;
      case PARENT -> 1;
      case OWNER -> 2;
    };
  }
}
