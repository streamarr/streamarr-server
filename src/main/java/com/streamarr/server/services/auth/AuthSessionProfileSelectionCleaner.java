package com.streamarr.server.services.auth;

import com.streamarr.server.repositories.auth.AuthSessionRepository;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthSessionProfileSelectionCleaner implements ProfileSelectionCleaner {

  private final AuthSessionRepository sessionRepository;
  private final Clock clock;

  @Override
  public int clear(UUID profileId, UUID householdId) {
    return sessionRepository.clearProfileSelection(profileId, householdId, clock.instant());
  }
}
