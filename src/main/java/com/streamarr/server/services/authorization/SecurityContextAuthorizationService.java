package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenScope;
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
  private final UserAccountRepository userAccountRepository;

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
  public <T> Decision<T> decideForAccount(UUID accountId, Intent<T> intent) {
    return userAccountRepository
        .findById(accountId)
        .<Decision<T>>map(account -> decider.decide(storedIdentity(account), intent))
        .orElseGet(() -> new Decision.Denied<>(Decision.DenialReason.POLICY));
  }

  @Override
  public <T> T requireAllowed(AuthenticatedIdentity identity, Intent<T> intent) {
    return switch (decide(identity, intent)) {
      case Decision.Allowed<T>(var value) -> value;
      case Decision.Denied<T> _ -> throw new AccessDeniedException("Not allowed.");
      case Decision.Failed<T> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private static AuthenticatedIdentity storedIdentity(UserAccount account) {
    return AuthenticatedIdentity.builder()
        .accountId(account.getId())
        .authSessionId(new UUID(0, 0))
        .scope(TokenScope.ACCOUNT)
        .householdId(account.getHouseholdId())
        .householdRole(account.getHouseholdRole())
        .serverAdmin(account.isServerAdmin())
        .contextHouseholdId(account.getHouseholdId())
        .build();
  }
}
