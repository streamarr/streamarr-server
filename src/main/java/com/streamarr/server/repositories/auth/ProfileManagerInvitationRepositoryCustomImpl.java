package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.INVALIDATED;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.PENDING;
import static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus.valueOf;
import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class ProfileManagerInvitationRepositoryCustomImpl
    implements ProfileManagerInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForProfile(
      UUID profileId, Instant now, Seek seek) {
    return rowsPendingBeyond(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId), now, seek);
  }

  @Override
  public List<Row<ProfileManagerInvitation>> rowsPendingForRecipient(
      UUID recipientAccountId, Instant now, Seek seek) {
    return rowsPendingBeyond(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId), now, seek);
  }

  private List<Row<ProfileManagerInvitation>> rowsPendingBeyond(
      Condition scope, Instant now, Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(PROFILE_MANAGER_INVITATION.CREATED_ON)
            .itemId(PROFILE_MANAGER_INVITATION.ID)
            .build();
    var live =
        scope
            .and(
                PROFILE_MANAGER_INVITATION.STATUS.eq(
                    inline(jooqStatus(ProfileManagerInvitationStatus.PENDING))))
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)));
    var query = keyset.rowsBeyond(keyset.selectFrom(dsl, PROFILE_MANAGER_INVITATION), live, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, ProfileManagerInvitation.class, keyset);
  }

  private boolean tryTransitionPending(
      UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
    return dsl.update(PROFILE_MANAGER_INVITATION)
            .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(target))
            .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .where(PROFILE_MANAGER_INVITATION.ID.eq(invitationId))
            .and(pending())
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .execute()
        > 0;
  }

  @Override
  public boolean tryCancelPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.CANCELED, now);
  }

  @Override
  public boolean tryAcceptPending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.ACCEPTED, now);
  }

  @Override
  public boolean tryDeclinePending(UUID invitationId, Instant now) {
    return tryTransitionPending(invitationId, ProfileManagerInvitationStatus.DECLINED, now);
  }

  @Override
  public boolean tryInvalidatePending(UUID invitationId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.ID.eq(invitationId), reason, now) > 0;
  }

  @Override
  public int invalidatePendingByProfileIdAndRecipientAccountId(
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
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    return invalidate(PROFILE_MANAGER_INVITATION.PROFILE_ID.eq(profileId), reason, now);
  }

  @Override
  public int invalidatePendingInvitationsByInviterAccountIdAndProfileId(
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
  public int invalidatePendingInvitedBy(UUID inviterAccountId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION.INVITER_ACCOUNT_ID.eq(inviterAccountId), reason, now);
  }

  @Override
  public int invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    return invalidate(
        PROFILE_MANAGER_INVITATION.RECIPIENT_ACCOUNT_ID.eq(recipientAccountId), reason, now);
  }

  private int invalidate(Condition scope, String reason, Instant now) {
    materializeExpiredPending(scope, now);
    return dsl.update(PROFILE_MANAGER_INVITATION)
        .set(PROFILE_MANAGER_INVITATION.STATUS, INVALIDATED)
        .set(PROFILE_MANAGER_INVITATION.INVALIDATION_REASON, reason)
        .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
        .where(scope)
        .and(pending())
        .execute();
  }

  private void materializeExpiredPending(Condition scope, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    dsl.update(PROFILE_MANAGER_INVITATION)
        .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(ProfileManagerInvitationStatus.EXPIRED))
        .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, nowUtc)
        .where(scope)
        .and(pending())
        .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.le(nowUtc))
        .execute();
  }

  private static Condition pending() {
    return PROFILE_MANAGER_INVITATION.STATUS.eq(PENDING);
  }

  // The domain and generated jOOQ status enums share a simple name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ProfileManagerInvitationStatus
      jooqStatus(ProfileManagerInvitationStatus status) {
    return valueOf(status.name());
  }
}
