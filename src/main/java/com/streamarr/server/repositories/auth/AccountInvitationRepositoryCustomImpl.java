package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountInvitation.ACCOUNT_INVITATION;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.jooq.generated.enums.AccountInvitationStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.SortOrder;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class AccountInvitationRepositoryCustomImpl implements AccountInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;
  private final InvitationIssuanceLock invitationIssuanceLock;

  @Override
  public List<AccountInvitation> findAdministrationPage(MediaPaginationOptions options) {
    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var createdOnOrder = reverse ? SortOrder.ASC : SortOrder.DESC;
    var idOrder = reverse ? SortOrder.DESC : SortOrder.ASC;
    var cursorCondition = administrationCursorCondition(options, reverse);
    var cursorFirst =
        options
            .getCursorId()
            .map(cursorId -> when(ACCOUNT_INVITATION.ID.eq(cursorId), 0).otherwise(1))
            .orElse(val(0));
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select(ACCOUNT_INVITATION.asterisk())
            .from(ACCOUNT_INVITATION)
            .where(cursorCondition)
            .orderBy(
                cursorFirst.asc(),
                ACCOUNT_INVITATION.CREATED_ON.sort(createdOnOrder),
                ACCOUNT_INVITATION.ID.sort(idOrder))
            .limit(options.getPaginationOptions().getLimit() + extraRows);
    var invitations = JooqQueryHelper.nativeQuery(entityManager, query, AccountInvitation.class);
    if (reverse) {
      Collections.reverse(invitations);
    }

    return invitations;
  }

  private Condition administrationCursorCondition(MediaPaginationOptions options, boolean reverse) {
    if (options.getCursorId().isEmpty()) {
      return noCondition();
    }

    var cursorId = options.getCursorId().orElseThrow();
    var cursorCreatedOn = cursorCreatedOn(options);
    var afterCursor =
        reverse
            ? ACCOUNT_INVITATION
                .CREATED_ON
                .gt(cursorCreatedOn)
                .or(
                    ACCOUNT_INVITATION
                        .CREATED_ON
                        .eq(cursorCreatedOn)
                        .and(ACCOUNT_INVITATION.ID.lt(cursorId)))
            : ACCOUNT_INVITATION
                .CREATED_ON
                .lt(cursorCreatedOn)
                .or(
                    ACCOUNT_INVITATION
                        .CREATED_ON
                        .eq(cursorCreatedOn)
                        .and(ACCOUNT_INVITATION.ID.gt(cursorId)));
    return ACCOUNT_INVITATION.ID.eq(cursorId).or(afterCursor);
  }

  private OffsetDateTime cursorCreatedOn(MediaPaginationOptions options) {
    var sortValue = options.getMediaFilter().getPreviousSortFieldValue();
    if (sortValue == null) {
      throw new InvalidPaginationCursorException("Cursor sort value is required.");
    }

    try {
      return Instant.parse(sortValue.toString()).atOffset(ZoneOffset.UTC);
    } catch (DateTimeParseException _) {
      throw new InvalidPaginationCursorException("Cursor sort value is invalid.");
    }
  }

  @Override
  public void lockInvitationIssuanceForRecipientEmail(String recipientEmail) {
    invitationIssuanceLock.lockRecipientEmail(recipientEmail);
  }

  @Override
  public boolean markAcceptedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.ACCEPTED, now);
  }

  @Override
  public boolean markDeclinedIfPendingAndUnexpired(UUID invitationId, Instant now) {
    return markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.DECLINED, now);
  }

  @Override
  public Optional<AccountInvitation> cancelIfPendingAndUnexpired(UUID invitationId, Instant now) {
    if (!markIfPendingAndUnexpired(invitationId, AccountInvitationStatus.CANCELED, now)) {
      return Optional.empty();
    }

    // The update above bypassed the persistence context; refresh so a caller that already loaded
    // this row does not get its stale managed copy back.
    var invitation = entityManager.find(AccountInvitation.class, invitationId);
    entityManager.refresh(invitation);
    return Optional.of(invitation);
  }

  private boolean markIfPendingAndUnexpired(
      UUID invitationId, AccountInvitationStatus target, Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
            .set(ACCOUNT_INVITATION.STATUS, target)
            .set(ACCOUNT_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
            .set(ACCOUNT_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(ACCOUNT_INVITATION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(ACCOUNT_INVITATION.ID.eq(invitationId))
            .and(pending())
            .and(ACCOUNT_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .execute()
        > 0;
  }

  @Override
  public int invalidatePendingInvitationsForRecipientEmail(
      String recipientEmail, String reason, Instant now) {
    return invalidate(
        ACCOUNT_INVITATION
            .RECIPIENT_EMAIL
            .equalIgnoreCase(recipientEmail)
            .and(ACCOUNT_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))),
        reason,
        now);
  }

  @Override
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    return invalidate(ACCOUNT_INVITATION.PROFILE_ID.eq(profileId), reason, now);
  }

  @Override
  public int invalidatePendingInvitationsIssuedBy(
      UUID issuerAccountId, String reason, Instant now) {
    return invalidate(
        ACCOUNT_INVITATION
            .ISSUER_ACCOUNT_ID
            .eq(issuerAccountId)
            .and(ACCOUNT_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))),
        reason,
        now);
  }

  @Override
  public int expirePendingInvitationsForRecipientEmail(String recipientEmail, Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
        .set(ACCOUNT_INVITATION.STATUS, AccountInvitationStatus.EXPIRED)
        .set(ACCOUNT_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(ACCOUNT_INVITATION.RECIPIENT_EMAIL.equalIgnoreCase(recipientEmail))
        .and(pending())
        .and(ACCOUNT_INVITATION.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  private int invalidate(Condition scope, String reason, Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
        .set(ACCOUNT_INVITATION.STATUS, AccountInvitationStatus.INVALIDATED)
        .set(ACCOUNT_INVITATION.INVALIDATION_REASON, reason)
        .set(ACCOUNT_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(pending())
        .execute();
  }

  private static Condition pending() {
    return ACCOUNT_INVITATION.STATUS.eq(AccountInvitationStatus.PENDING);
  }
}
