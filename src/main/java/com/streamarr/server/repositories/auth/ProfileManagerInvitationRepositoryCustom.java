package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.Instant;
import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  /** One decision wins: PENDING and unexpired flips to the target exactly once. */
  boolean tryDecide(UUID invitationId, ProfileManagerInvitationStatus target, Instant now);

  /** Replacement: at most one PENDING invitation per Profile and recipient. */
  int invalidatePendingForProfileAndRecipient(
      UUID profileId, UUID recipientAccountId, String reason, Instant now);

  /** Invalidates every PENDING invitation bound to the Profile (moved, deleted, connected). */
  int invalidatePendingForProfile(UUID profileId, String reason, Instant now);

  /** Invalidates a leaving inviter's outstanding proposals for one Profile. */
  int invalidatePendingInvitedBy(UUID inviterAccountId, UUID profileId, String reason, Instant now);

  /** Invalidates every PENDING invitation naming the recipient (deleted or ineligible). */
  int invalidatePendingForRecipient(UUID recipientAccountId, String reason, Instant now);
}
