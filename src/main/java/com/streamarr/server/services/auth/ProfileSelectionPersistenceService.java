package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileSelectionPersistenceService {

  private final AuthSessionRepository sessionRepository;

  /**
   * Selects the active profile for an authenticated session.
   *
   * @param accountId the account associated with the session
   * @param sessionId the session to update
   * @param profileId the profile to select
   * @return the updated authentication session
   * @throws AuthenticationRequiredException if the session does not belong to the account or has been revoked
   */
  @Transactional
  public AuthSession select(UUID accountId, UUID sessionId, UUID profileId) {
    var session =
        sessionRepository
            .lockById(sessionId)
            .filter(candidate -> candidate.getAccountId().equals(accountId))
            .filter(candidate -> candidate.getRevokedAt() == null)
            .orElseThrow(AuthenticationRequiredException::new);
    session.setActiveProfileId(profileId);
    return sessionRepository.save(session);
  }
}
