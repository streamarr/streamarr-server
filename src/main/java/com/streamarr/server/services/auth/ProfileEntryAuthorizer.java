package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProfileEntryAuthorizer {

  private final ProfilePinService pinService;
  private final CredentialGuessThrottle throttle;
  private final SecurityAuditService auditService;

  public void requireEntry(UUID accountId, Profile profile, String pin) {
    if (!pinService.requiresEntry(profile)) {
      return;
    }

    throttle.registerProfilePinAttempt(accountId, profile.getId());
    try {
      pinService.requireEntry(profile, pin);
    } catch (ProfileAccessDeniedException exception) {
      auditService.recordFailure(
          SecurityAuditRecord.builder()
              .actingAccountId(accountId)
              .targetProfileId(profile.getId())
              .operation(SecurityAuditOperation.PROFILE_PIN_ENTRY_DENIED)
              .reason("Profile PIN verification failed")
              .build());
      throw exception;
    }
    throttle.resetProfilePinAttempts(accountId, profile.getId());
  }
}
