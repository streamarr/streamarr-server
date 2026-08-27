package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;

import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

// checkstyle:fullyQualifiedName suppressed for the class: the domain and generated jOOQ status
// enums share a simple name, an unavoidable collision.
@SuppressWarnings("checkstyle:fullyQualifiedName")
@RequiredArgsConstructor
public class ProfileManagerInvitationRepositoryCustomImpl
    implements ProfileManagerInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public boolean tryDecide(UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
    return dsl.update(PROFILE_MANAGER_INVITATION)
            .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(target))
            .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY,
                auditorAware.getCurrentAuditor().orElse(null))
            .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
            .and(pending())
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .execute()
        > 0;
  }

  @Override
  public int invalidatePendingForProfileAndRecipient(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId)),
        reason,
        now);
  }

  @Override
  public int invalidatePendingForProfile(UUID profileId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId), reason, now);
  }

  @Override
  public int invalidatePendingInvitedBy(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION
            .INVITER_ACCOUNT_ID
            .eq(inviterAccountId)
            .and(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId)),
        reason,
        now);
  }

  @Override
  public int invalidatePendingForRecipient(UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId), reason, now);
  }

  private int invalidate(Condition scope, String reason, Instant now) {
    return dsl.update(PROFILE_MANAGER_INVITATION)
        .set(
            PROFILE_MANAGER_INVITATION.STATUS,
            com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.INVALIDATED)
        .set(PROFILE_MANAGER_INVITATION.INVALIDATION_REASON, reason)
        .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
        .set(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(
            PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY,
            auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(pending())
        .execute();
  }

  private static Condition pending() {
    return PROFILE_MANAGER_INVITATION.STATUS.eq(
        com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING);
  }

  private static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus
      jooqStatus(ProfileManagerInvitationStatus status) {
    return com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.valueOf(
        status.name());
  }
}
