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
  private final ProfileAvailabilityService profileAvailabilityService;

  @Transactional
  public AuthSession select(UUID accountId, UUID sessionId, UUID profileId) {
    var session =
        sessionRepository
            .lockById(sessionId)
            .filter(candidate -> candidate.getAccountId().equals(accountId))
            .filter(candidate -> candidate.getRevokedAt() == null)
            .orElseThrow(AuthenticationRequiredException::new);
    profileAvailabilityService.requireSelectableProfile(accountId, profileId);
    session.setActiveProfileId(profileId);
    return sessionRepository.save(session);
  }
}
