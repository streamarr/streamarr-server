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

  /**
   * Retrieves the authenticated identity from the current security context.
   *
   * @return the authenticated identity
   * @throws AuthenticationRequiredException if no supported authentication is present
   */
  @Override
  public AuthenticatedIdentity currentIdentity() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return token.getPrincipal();
    }
    throw new AuthenticationRequiredException();
  }

  /**
   * Retrieves the value of the current authentication token.
   *
   * @return the current token value
   */
  @Override
  public String currentTokenValue() {
    return currentJwt().getTokenValue();
  }

  /**
   * Retrieves the expiration time of the current authentication token.
   *
   * @return the token's expiration time
   * @throws AuthenticationRequiredException if the token has no expiration time
   */
  @Override
  public Instant currentTokenExpiry() {
    var expiry = currentJwt().getExpiresAt();
    if (expiry != null) {
      return expiry;
    }
    throw new AuthenticationRequiredException();
  }

  /**
   * Retrieves the authenticated account identifier.
   *
   * @return the authenticated account identifier
   */
  @Override
  public UUID requireAccountId() {
    return state().account().getId();
  }

  /**
   * Retrieves the identifier of the authenticated account's home household.
   *
   * @return the home household identifier
   */
  @Override
  public UUID requireHousehold() {
    return state().account().getHomeHouseholdId();
  }

  /**
   * Requires an active profile for the current authentication context.
   *
   * @return the active profile identifier
   * @throws ProfileRequiredException if no profile is active
   */
  @Override
  public UUID requireProfile() {
    var profileId = state().activeProfileId();
    if (profileId != null) {
      return profileId;
    }
    throw new ProfileRequiredException();
  }

  /**
   * Requires an active profile and creates playback authorization for the current session.
   *
   * @return playback authorization containing the authentication session, account, household, and profile identifiers
   * @throws ProfileRequiredException if no profile is active
   */
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

  /**
   * Determines whether the current account has server administrator privileges.
   *
   * @return {@code true} if the account role is {@code ADMIN}, {@code false} otherwise
   */
  @Override
  public boolean isServerAdmin() {
    return state().account().getAccountRole() == AccountRole.ADMIN;
  }

  /**
   * Requires the current account to have server administrator privileges.
   *
   * @throws AccessDeniedException if the current account is not a server administrator
   */
  @Override
  public void requireServerAdmin() {
    if (!isServerAdmin()) {
      throw new AccessDeniedException("Server administrator role is required.");
    }
  }

  /**
   * Requires the authenticated account to have at least the specified household role.
   *
   * @param minimum the minimum household role required
   * @throws AccessDeniedException if the account's household role is insufficient
   */
  @Override
  public void requireHouseholdRole(HouseholdRole minimum) {
    var actual = state().account().getHouseholdRole();
    if (rank(actual) < rank(minimum)) {
      throw new AccessDeniedException("Household role " + minimum + " or higher is required.");
    }
  }

  /**
   * Determines whether the current user can view activity for a profile.
   *
   * @param profileId the profile whose activity access is being checked
   * @return {@code true} for administrators, the active profile, or profiles actively shared with a household where the user has a parent-level role; {@code false} otherwise
   */
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

  /**
   * Resolves authorization state from the current authenticated security context.
   *
   * @return the current authorization state
   * @throws AuthenticationRequiredException if no supported authentication token is present
   */
  private RequestAuthorizationStateResolver.AuthorizationState state() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token) {
      return stateResolver.resolve(token);
    }
    throw new AuthenticationRequiredException();
  }

  /**
   * Retrieves the JWT associated with the current authenticated request.
   *
   * @return the current JWT
   * @throws AuthenticationRequiredException if the request does not have a valid authentication token
   */
  private Jwt currentJwt() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof StreamarrAuthenticationToken token
        && token.getCredentials() instanceof Jwt jwt) {
      return jwt;
    }
    throw new AuthenticationRequiredException();
  }

  /**
   * Assigns an authority ranking to a household role.
   *
   * @param role the household role to rank
   * @return the role's authority level
   */
  private static int rank(HouseholdRole role) {
    return switch (role) {
      case MEMBER -> 0;
      case PARENT -> 1;
      case OWNER -> 2;
    };
  }
}
