package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.UnwrittenAuthSessionException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.TokenContext;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the session's remembered viewing context — the context Household and the selected Profile
 * (ADR 0024). Refresh revalidates the stored context against live relationships and never the
 * client's word: a Household the Account may no longer use falls back to the membership Household
 * and the Profile picker; a selected Profile that is no longer available or is now locked is
 * cleared.
 */
@Service
@RequiredArgsConstructor
public class SessionContextService {

  private final LiveSessions liveSessions;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final AuthSessionRepository sessionRepository;
  private final Clock clock;

  /** Records an authorized selection on the live session (the caller has already decided). */
  @Transactional
  public TokenContext recordProfileSelection(AuthenticatedIdentity identity, UUID profileId) {
    var account = liveSessions.loadAccount(identity.accountId());
    var session = liveSessions.lockLiveSession(identity.accountId(), identity.authSessionId());
    if (!identity.contextHouseholdId().equals(session.getContextHouseholdId())) {
      throw new ProfileAccessDeniedException();
    }
    session.setSelectedProfileId(profileId);
    persistSelection(session);
    return TokenContext.of(account, session);
  }

  @Transactional
  public TokenContext revalidateStoredContext(UserAccount account, AuthSession session) {
    var contextHouseholdId = session.getContextHouseholdId();
    if (contextHouseholdId == null
        || !userAccountRepository.mayUseHousehold(account.getId(), contextHouseholdId)) {
      return fallBackToMembership(account, session);
    }

    var selectedProfileId = session.getSelectedProfileId();
    if (selectedProfileId != null && !selectable(contextHouseholdId, selectedProfileId)) {
      session.setSelectedProfileId(null);
      persistSelection(session);
    }
    return TokenContext.of(account, session);
  }

  private TokenContext fallBackToMembership(UserAccount account, AuthSession session) {
    session.setContextHouseholdId(account.getHouseholdId());
    session.setSelectedProfileId(null);
    persistSelection(session);
    return TokenContext.of(account, session);
  }

  private boolean selectable(UUID householdId, UUID profileId) {
    var available = profileRepository.findAvailableInHousehold(householdId);
    var stillAvailable = available.stream().anyMatch(profile -> profile.getId().equals(profileId));
    return stillAvailable && !ProfileSafetyRule.lockedProfiles(available).contains(profileId);
  }

  /**
   * Shared by the selection services: writes the remembered context only while the session lives.
   */
  void persistSelection(AuthSession session) {
    if (sessionRepository.updateSelectionIfLive(session, clock.instant())) {
      return;
    }
    throw classifyLostSelection(session.getId());
  }

  /**
   * Zero rows updated has two causes and only one of them is an authentication failure: a revoked
   * session is genuinely unauthenticated, while a session with no row at all is a caller whose
   * insert is still queued behind this write. Answering the second as the first told a paired
   * device to authenticate over a fault it could do nothing about.
   */
  private RuntimeException classifyLostSelection(UUID sessionId) {
    if (sessionRepository.hasRow(sessionId)) {
      return new AuthenticationRequiredException();
    }
    return new UnwrittenAuthSessionException(sessionId);
  }
}
