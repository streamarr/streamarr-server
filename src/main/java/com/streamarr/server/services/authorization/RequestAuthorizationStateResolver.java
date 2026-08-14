package com.streamarr.server.services.authorization;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class RequestAuthorizationStateResolver {

  private final UserAccountRepository accountRepository;
  private final AuthSessionRepository sessionRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final MutexFactory<StreamarrAuthenticationToken> authenticationMutex;

  public RequestAuthorizationStateResolver(
      UserAccountRepository accountRepository,
      AuthSessionRepository sessionRepository,
      ProfileHouseholdShareRepository shareRepository,
      MutexFactoryProvider mutexFactoryProvider) {
    this.accountRepository = accountRepository;
    this.sessionRepository = sessionRepository;
    this.shareRepository = shareRepository;
    this.authenticationMutex = mutexFactoryProvider.getMutexFactory();
  }

  public AuthorizationState resolve(StreamarrAuthenticationToken authentication) {
    var mutex = authenticationMutex.getMutex(authentication);
    mutex.lock();
    try {
      if (authentication.getDetails() instanceof CachedAuthorizationState(var cachedState)) {
        return cachedState;
      }

      var state = load(authentication.getPrincipal());
      authentication.setDetails(new CachedAuthorizationState(state));
      return state;
    } finally {
      mutex.unlock();
    }
  }

  private AuthorizationState load(AuthenticatedIdentity signedIdentity) {
    var account = loadEnabledAccount(signedIdentity.accountId());
    requireLiveSession(signedIdentity, account);
    var activeProfileId = resolveActiveProfile(signedIdentity, account);
    return new AuthorizationState(account, activeProfileId);
  }

  private UserAccount loadEnabledAccount(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .filter(UserAccount::isEnabled)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private void requireLiveSession(AuthenticatedIdentity identity, UserAccount account) {
    sessionRepository
        .findById(identity.authSessionId())
        .filter(session -> session.getAccountId().equals(account.getId()))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private UUID resolveActiveProfile(AuthenticatedIdentity identity, UserAccount account) {
    var profileId = identity.profileId();
    if (profileId == null) {
      return null;
    }
    if (shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
        profileId, account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE)) {
      return profileId;
    }
    return null;
  }

  private record CachedAuthorizationState(AuthorizationState state) {}

  public record AuthorizationState(UserAccount account, UUID activeProfileId) {}
}
