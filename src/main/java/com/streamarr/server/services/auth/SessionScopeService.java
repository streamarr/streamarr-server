package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.UnwrittenAuthSessionException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the session's optional active portable profile. */
@Service
@RequiredArgsConstructor
public class SessionScopeService {

  private final ProfileAvailabilityService profileAvailabilityService;
  private final AuthSessionRepository sessionRepository;
  private final UserAccountRepository userAccountRepository;
  private final Clock clock;

  @Transactional
  public TokenContext revalidateStoredContext(UserAccount account, AuthSession session) {
    var profileId = session.getActiveProfileId();
    if (profileId == null) {
      return TokenContext.builder().account(account).session(session).build();
    }

    try {
      profileAvailabilityService.requireSelectableProfile(account.getId(), profileId);
      return TokenContext.builder().account(account).session(session).profileId(profileId).build();
    } catch (ProfileAccessDeniedException _) {
      session.setActiveProfileId(null);
      persistSelection(session);
      return TokenContext.builder().account(account).session(session).build();
    }
  }

  @Transactional
  public TokenContext selectProfile(UUID accountId, UUID sessionId, UUID profileId) {
    var account = loadAccount(accountId);
    var session = loadLiveSession(accountId, sessionId);

    profileAvailabilityService.requireSelectableProfile(accountId, profileId);
    session.setActiveProfileId(profileId);
    sessionRepository.save(session);

    return TokenContext.builder().account(account).session(session).profileId(profileId).build();
  }

  private UserAccount loadAccount(UUID accountId) {
    return userAccountRepository
        .findById(accountId)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private AuthSession loadLiveSession(UUID accountId, UUID sessionId) {
    return sessionRepository
        .lockById(sessionId)
        .filter(session -> session.getAccountId().equals(accountId))
        .filter(session -> session.getRevokedAt() == null)
        .orElseThrow(AuthenticationRequiredException::new);
  }

  private void persistSelection(AuthSession session) {
    if (sessionRepository.updateSelectionIfLive(session, clock.instant())) {
      return;
    }

    if (sessionRepository.hasRow(session.getId())) {
      throw new AuthenticationRequiredException();
    }

    throw new UnwrittenAuthSessionException(session.getId());
  }
}
