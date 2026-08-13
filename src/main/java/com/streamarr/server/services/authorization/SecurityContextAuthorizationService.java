package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
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

  private final RequestAuthorizationStateResolver stateResolver;
  private final ProfileHouseholdShareRepository shareRepository;

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
    return currentJwt().getTokenValue();
  }

  @Override
  public Instant currentTokenExpiry() {
    var expiry = currentJwt().getExpiresAt();
    if (expiry != null) {
      return expiry;
    }
    throw new AuthenticationRequiredException();
  }

  @Override
  public UUID requireAccountId() {
    return state().account().getId();
  }

  @Override
  public UUID requireHousehold() {
    return state().account().getHomeHouseholdId();
  }

  @Override
  public UUID requireProfile() {
    var profileId = state().activeProfileId();
    if (profileId != null) {
      return profileId;
    }
    throw new ProfileRequiredException();
  }

  @Override
  public PlaybackAuthority requirePlaybackAuthority() {
    var state = state();
    var profileId = state.activeProfileId();
    if (profileId == null) {
      throw new ProfileRequiredException();
    }
    return PlaybackAuthority.builder()
        .authSessionId(currentIdentity().authSessionId())
        .accountId(state.account().getId())
        .householdId(state.account().getHomeHouseholdId())
        .profileId(profileId)
        .build();
  }

  @Override
  public boolean isServerAdmin() {
    return state().account().getAccountRole() == AccountRole.ADMIN;
  }

  @Override
  public void requireServerAdmin() {
    if (!isServerAdmin()) {
      throw new AccessDeniedException("Server administrator role is required.");
    }
  }

  @Override
  public void requireHouseholdRole(HouseholdRole minimum) {
    var actual = state().account().getHouseholdRole();
    if (rank(actual) < rank(minimum)) {
      throw new AccessDeniedException("Household role " + minimum + " or higher is required.");
    }
  }

  @Override
  public boolean canViewActivityOf(UUID profileId) {
    if (profileId == null) {
      return false;
    }

    var state = state();
    if (state.account().getAccountRole() == AccountRole.ADMIN
        || profileId.equals(state.activeProfileId())) {
      return true;
    }
    if (rank(state.account().getHouseholdRole()) < rank(HouseholdRole.PARENT)) {
      return false;
    }
    return shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
        profileId, state.account().getHomeHouseholdId(), ProfileShareStatus.ACTIVE);
  }

  private RequestAuthorizationStateResolver.AuthorizationState state() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return stateResolver.resolve(token);
    }
    throw new AuthenticationRequiredException();
  }

  private Jwt currentJwt() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token
        && token.getCredentials() instanceof Jwt jwt) {
      return jwt;
    }
    throw new AuthenticationRequiredException();
  }

  private static int rank(HouseholdRole role) {
    return switch (role) {
      case MEMBER -> 0;
      case PARENT -> 1;
      case OWNER -> 2;
    };
  }
}
