package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.data.domain.AuditorAware;

// checkstyle:fullyQualifiedName suppressed for the class: the domain and generated jOOQ status
// enums share a simple name, an unavoidable collision.
@SuppressWarnings("checkstyle:fullyQualifiedName")
@RequiredArgsConstructor
public class ProfileManagerInvitationRepositoryCustomImpl
    implements ProfileManagerInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;

  @Override
  public List<ProfileManagerInvitation> findPendingByProfileId(
      UUID profileId, Instant now, KeysetPaginationOptions options) {
    return findPage(
        PROFILE_MANAGER_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(
                PROFILE_MANAGER_INVITATION.STATUS.eq(
                    inline(jooqStatus(ProfileManagerInvitationStatus.PENDING))))
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))),
        options);
  }

  @Override
  public List<ProfileManagerInvitation> findPendingByRecipientAccountId(
      UUID recipientAccountId, Instant now, KeysetPaginationOptions options) {
    return findPage(
        PROFILE_MANAGER_INVITATION
            .RECIPIENT_ACCOUNT_ID
            .eq(recipientAccountId)
            .and(
                PROFILE_MANAGER_INVITATION.STATUS.eq(
                    inline(jooqStatus(ProfileManagerInvitationStatus.PENDING))))
            .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))),
        options);
  }

  private List<ProfileManagerInvitation> findPage(
      Condition scope, KeysetPaginationOptions options) {
    var pagination = options.getPaginationOptions();
    var reverse = pagination.getPaginationDirection() == PaginationDirection.REVERSE;
    var cursorCondition = cursorCondition(scope, options, reverse);
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select()
            .from(PROFILE_MANAGER_INVITATION)
            .where(scope)
            .and(cursorCondition)
            .orderBy(
                reverse
                    ? PROFILE_MANAGER_INVITATION.CREATED_ON.asc()
                    : PROFILE_MANAGER_INVITATION.CREATED_ON.desc(),
                reverse
                    ? PROFILE_MANAGER_INVITATION.ID.desc()
                    : PROFILE_MANAGER_INVITATION.ID.asc())
            .limit(pagination.getLimit() + extraRows);
    var found = JooqQueryHelper.nativeQuery(entityManager, query, ProfileManagerInvitation.class);
    if (reverse) {
      Collections.reverse(found);
    }

    return found;
  }

  private Condition cursorCondition(
      Condition scope, KeysetPaginationOptions options, boolean reverse) {
    return options
        .getCursorId()
        .<Condition>map(cursorId -> cursorCondition(scope, cursorId, reverse))
        .orElseGet(DSL::noCondition);
  }

  private Condition cursorCondition(Condition scope, UUID cursorId, boolean reverse) {
    var cursor =
        dsl.select(PROFILE_MANAGER_INVITATION.CREATED_ON, PROFILE_MANAGER_INVITATION.ID)
            .from(PROFILE_MANAGER_INVITATION)
            .where(scope)
            .and(PROFILE_MANAGER_INVITATION.ID.eq(cursorId))
            .fetchOptional(row -> new InvitationCursor(row.value1(), row.value2()))
            .orElseThrow(
                () -> new InvalidPaginationCursorException("Cursor no longer identifies an item."));
    var sameCreatedOn = PROFILE_MANAGER_INVITATION.CREATED_ON.eq(cursor.createdOn());
    if (reverse) {
      return PROFILE_MANAGER_INVITATION
          .CREATED_ON
          .gt(cursor.createdOn())
          .or(sameCreatedOn.and(PROFILE_MANAGER_INVITATION.ID.le(cursor.id())));
    }

    return PROFILE_MANAGER_INVITATION
        .CREATED_ON
        .lt(cursor.createdOn())
        .or(sameCreatedOn.and(PROFILE_MANAGER_INVITATION.ID.ge(cursor.id())));
  }

  private record InvitationCursor(OffsetDateTime createdOn, UUID id) {}

  private boolean tryTransitionPending(
      UUID invitationId, ProfileManagerInvitationStatus target, Instant now) {
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
  public int invalidatePendingForInviter(UUID inviterAccountId, String reason, Instant now) {
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

  private void materializeExpiredPending(Condition scope, Instant now) {
    var nowUtc = now.atOffset(ZoneOffset.UTC);
    dsl.update(PROFILE_MANAGER_INVITATION)
        .set(PROFILE_MANAGER_INVITATION.STATUS, jooqStatus(ProfileManagerInvitationStatus.EXPIRED))
        .set(PROFILE_MANAGER_INVITATION.DECIDED_AT, nowUtc)
        .set(PROFILE_MANAGER_INVITATION.LAST_MODIFIED_ON, nowUtc)
        .set(
            PROFILE_MANAGER_INVITATION.LAST_MODIFIED_BY,
            auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(pending())
        .and(PROFILE_MANAGER_INVITATION.EXPIRES_AT.le(nowUtc))
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
