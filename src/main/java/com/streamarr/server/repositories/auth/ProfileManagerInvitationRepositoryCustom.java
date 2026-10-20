package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  /** The Profile's live PENDING invitations beyond the seek, by creation time, then ID. */
  List<Row<ProfileManagerInvitation>> rowsPendingForProfile(UUID profileId, Instant now, Seek seek);

  /** The recipient's live PENDING invitations beyond the seek, by creation time, then ID. */
  List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek);

  /** Cancels one live PENDING invitation exactly once. */
  boolean tryCancelPending(UUID invitationId, Instant now);

  /** Accepts one live PENDING invitation exactly once. */
  boolean tryAcceptPending(UUID invitationId, Instant now);

  /** Declines one live PENDING invitation exactly once. */
  boolean tryDeclinePending(UUID invitationId, Instant now);

  /** Invalidates one PENDING invitation without touching a replacement for the same parties. */
  boolean tryInvalidatePending(UUID invitationId, String reason, Instant now);

  /** Replacement: at most one PENDING invitation per Profile and recipient. */
  int invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now);

  /** Invalidates every PENDING invitation bound to the Profile (moved, deleted, linked). */
  int invalidatePendingByProfileId(UUID profileId, String reason, Instant now);

  /** Invalidates a leaving inviter's outstanding proposals for one Profile. */
  int invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now);

  /** Every PENDING invitation the inviter proposed, across all Profiles, becomes INVALIDATED. */
  int invalidatePendingInvitedBy(UUID inviterAccountId, String reason, Instant now);

  /** Invalidates every PENDING invitation naming the recipient (deleted or ineligible). */
  int invalidatePendingByRecipientAccountId(UUID recipientAccountId, String reason, Instant now);
}
