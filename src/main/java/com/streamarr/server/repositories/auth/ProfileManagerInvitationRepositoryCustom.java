package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationsFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileManagerInvitationRepositoryCustom {

  /**
   * The Profile's live PENDING invitations beyond the seek, as the filter's reader reads them, by
   * creation time, then ID.
   */
  List<Row<ProfileManagerInvitation>> rowsPendingForProfile(
      ProfileManagerInvitationsFilter filter, Instant now, Seek seek);

  /**
   * The recipient's live PENDING invitations that it may still answer, beyond the seek, by creation
   * time, then ID.
   */
  List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek);

  /**
   * Whether a live PENDING invitation waits for the Profile, as its ProfileManagers read it: one
   * invalidated for its invited Account still waits to them.
   */
  boolean existsWaitingForProfile(UUID profileId, Instant now);

  /**
   * How many live PENDING invitations wait for the Profile, leaving out the one to the address,
   * whatever its case, which a new invitation to that address would replace.
   */
  int countLivePendingForProfileExceptTo(UUID profileId, String email, Instant now);

  /** Cancels one live PENDING invitation exactly once; empty when it was not live and pending. */
  Optional<ChangedManagerInvitation> tryCancelPending(UUID invitationId, Instant now);

  /**
   * Accepts one live PENDING invitation exactly once; empty when it was not live and pending, or
   * was invalidated for its invited Account.
   */
  Optional<ChangedManagerInvitation> tryAcceptPending(UUID invitationId, Instant now);

  /**
   * Declines one live PENDING invitation exactly once; empty when it was not live and pending, or
   * was invalidated for its invited Account.
   */
  Optional<ChangedManagerInvitation> tryDeclinePending(UUID invitationId, Instant now);

  /*
   * Each invalidation below first stores EXPIRED on the PENDING invitations in its scope whose time
   * ran out, then invalidates the rest, and answers every invitation it changed with the status it
   * stored.
   */

  /** Invalidates one PENDING invitation without touching a replacement for the same parties. */
  List<ChangedManagerInvitation> tryInvalidatePending(
      UUID invitationId, String reason, Instant now);

  /** Replacement: at most one PENDING invitation per Profile and address, whatever its case. */
  List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientEmail(
      UUID profileId, String email, String reason, Instant now);

  /**
   * Invalidates, for the Account alone, the PENDING invitations for the Profile that name it: their
   * status stays PENDING, as the Profile's ProfileManagers read it.
   */
  List<ChangedManagerInvitation> invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now);

  /** Invalidates every PENDING invitation bound to the Profile (moved, deleted, linked). */
  List<ChangedManagerInvitation> invalidatePendingByProfileId(
      UUID profileId, String reason, Instant now);

  /** Invalidates a leaving inviter's outstanding proposals for one Profile. */
  List<ChangedManagerInvitation> invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now);

  /** Every PENDING invitation the inviter proposed, across all Profiles, becomes INVALIDATED. */
  List<ChangedManagerInvitation> invalidatePendingInvitedBy(
      UUID inviterAccountId, String reason, Instant now);

  /**
   * Invalidates, for the Account alone, every PENDING invitation naming it (deleted or ineligible):
   * their status stays PENDING, as each Profile's ProfileManagers read it.
   */
  List<ChangedManagerInvitation> invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now);

  /** The ids of every invitation to manage the Profile, whatever its status, read as scalars. */
  List<UUID> findIdsByProfileId(UUID profileId);

  /** Stores EXPIRED on every PENDING invitation whose time ran out, and answers each of them. */
  List<ChangedManagerInvitation> materializeExpiredPending(Instant now);
}
