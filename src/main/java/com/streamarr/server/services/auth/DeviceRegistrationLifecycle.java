package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Revoking a Device registration and its sessions is one move (ADR 0024 §Devices): a registration
 * without refresh authority is how "removes device access at refresh" is kept true, and T10 refuses
 * any ESN block that leaves either behind.
 */
@Service
@RequiredArgsConstructor
public class DeviceRegistrationLifecycle {

  private final DeviceRegistrationRepository registrationRepository;
  private final AuthSessionRepository authSessionRepository;

  /** Revokes one registration and its sessions; false when it was not ACTIVE. */
  public boolean revoke(UUID registrationId, UUID actorAccountId, String reason, Instant now) {
    var revoked = registrationRepository.tryRevoke(registrationId, actorAccountId, reason, now);
    if (revoked) {
      dropSessions(List.of(registrationId), now);
    }

    return revoked;
  }

  public void revokeAllByEsn(
      String esn, UUID householdId, UUID actorAccountId, String reason, Instant now) {
    dropSessions(
        registrationRepository.revokeAllByEsn(esn, householdId, actorAccountId, reason, now), now);
  }

  public void revokeAllByAccountAndHousehold(
      UUID authorizingAccountId, UUID householdId, String reason, Instant now) {
    dropSessions(
        registrationRepository.revokeAllByAccountAndHousehold(
            authorizingAccountId, householdId, reason, now),
        now);
  }

  public void revokeAllByAccount(UUID authorizingAccountId, String reason, Instant now) {
    dropSessions(registrationRepository.revokeAllByAccount(authorizingAccountId, reason, now), now);
  }

  private void dropSessions(List<UUID> registrationIds, Instant now) {
    authSessionRepository.revokeAllForRegistrations(
        registrationIds, SessionRevocationReason.ADMIN_REVOCATION, now);
  }
}
