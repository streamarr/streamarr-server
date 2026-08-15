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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RequestAuthorizationStateResolver {

  private final UserAccountRepository accountRepository;
  private final AuthSessionRepository sessionRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final MutexFactory<Object> authenticationMutex;

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

  /**
   * Resolves and caches authorization state for an authenticated request.
   *
   * @param authentication the authentication token containing the request identity and cached state
   * @return the resolved authorization state
   */
  public AuthorizationState resolve(StreamarrAuthenticationToken authentication) {
    var mutex = authenticationMutex.getMutex(authentication.getRequestAuthorizationMutexKey());
    mutex.lock();
    try {
      var cachedState = authentication.getRequestAuthorizationState();
      if (cachedState != null) {
        return cachedState;
      }

      var state = load(authentication.getPrincipal());
      authentication.setRequestAuthorizationState(state);
      return state;
    } finally {
      mutex.unlock();
    }
  }

  /**
   * Loads the authorization state for an authenticated identity.
   *
   * @param signedIdentity the authenticated identity to authorize
   * @return the account and active profile associated with the identity
   */
  private AuthorizationState load(AuthenticatedIdentity signedIdentity) {
    var account = loadEnabledAccount(signedIdentity.accountId());
    requireLiveSession(signedIdentity, account);
    var activeProfileId = resolveActiveProfile(signedIdentity, account);
    return new AuthorizationState(account, activeProfileId);
  }

  /**
   * Loads the enabled account identified by the specified ID.
   *
   * @param accountId the account identifier
   * @return the enabled user account
   * @throws AuthenticationRequiredException if the account does not exist or is disabled
   */
  private UserAccount loadEnabledAccount(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .filter(UserAccount::isEnabled)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * Ensures that the authenticated identity refers to a non-revoked session belonging to the account.
   *
   * @param identity the authenticated identity whose session is validated
   * @param account the account the session must belong to
   * @throws AuthenticationRequiredException if the session is missing, belongs to another account, or has been revoked
   */
  private void requireLiveSession(AuthenticatedIdentity identity, UserAccount account) {
    sessionRepository
        .findById(identity.authSessionId())
        .filter(session -> session.getAccountId().equals(account.getId()))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  /**
   * Resolves the authenticated profile when it has an active share in the account's home household.
   *
   * @param identity the authenticated identity containing the profile reference
   * @param account the account whose home household must contain the active profile share
   * @return the active profile ID, or {@code null} when no active profile share exists
   */
  private UUID resolveActiveProfile(AuthenticatedIdentity identity, UserAccount account) {
    var profileId = identity.profileId();
    if (profileId == null) {
      return null;
    }
    if (shareRepository.existsByProfileIdAndHouseholdIdAndStatus(
        profileId, account.getHomeHouseholdId(), ProfileShareStatus.ACTIVE)) {
      return profileId;
    }
    log.debug(
        "Profile {} is no longer active in account home {}; resolving request at account scope.",
        profileId,
        account.getHomeHouseholdId());
    return null;
  }

  public record AuthorizationState(UserAccount account, UUID activeProfileId) {}
}
