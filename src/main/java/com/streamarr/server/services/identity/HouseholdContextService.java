package com.streamarr.server.services.identity;

import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.TokenContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Household switching in Streamarr-web (ADR 0024 §Household): an Account may use a Household as a
 * member or as a visitor whose Personal Profile is actively shared there. Switching validates that
 * live, records the choice on the session, and always clears the selected Profile — a mismatched
 * Household/Profile pair can never be minted.
 */
@Service
@RequiredArgsConstructor
public class HouseholdContextService {

  private final LiveSessions liveSessions;
  private final UserAccountRepository userAccountRepository;
  private final SessionContextService sessionContextService;

  @Transactional
  public TokenContext selectHousehold(UUID accountId, UUID sessionId, UUID householdId) {
    var account = liveSessions.loadAccount(accountId);
    var session = liveSessions.lockLiveSession(accountId, sessionId);

    if (!userAccountRepository.mayUseHousehold(accountId, householdId)) {
      throw new HouseholdAccessDeniedException();
    }

    session.setContextHouseholdId(householdId);
    session.setSelectedProfileId(null);
    sessionContextService.persistSelection(session);

    return TokenContext.of(account, session);
  }
}
