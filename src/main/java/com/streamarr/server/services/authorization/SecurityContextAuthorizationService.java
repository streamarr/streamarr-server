package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileRequiredException;
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
    return currentIdentity().contextHouseholdId();
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
}
