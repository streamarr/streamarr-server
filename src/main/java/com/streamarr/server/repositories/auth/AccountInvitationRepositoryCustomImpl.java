package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountInvitation.ACCOUNT_INVITATION;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.jooq.generated.enums.AccountInvitationStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class AccountInvitationRepositoryCustomImpl implements AccountInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final InvitationIssuanceLock invitationIssuanceLock;

  @Override
  public List<Row<AccountInvitation>> rowsBeyond(Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(ACCOUNT_INVITATION.CREATED_ON)
            .itemId(ACCOUNT_INVITATION.ID)
            .build();
    var query =
        keyset.rowsBeyond(
            dsl.select(ACCOUNT_INVITATION.asterisk(), keyset.selectedSortValue())
                .from(ACCOUNT_INVITATION),
            seek);
    return JooqQueryHelper.nativeRows(entityManager, query, AccountInvitation.class, keyset);
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
  public int invalidatePendingByHouseholdId(UUID householdId, String reason, Instant now) {
    return invalidate(ACCOUNT_INVITATION.HOUSEHOLD_ID.eq(householdId), reason, now);
  }

  @Override
  public int invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    return invalidate(
        ACCOUNT_INVITATION
            .PROFILE_ID
            .eq(profileId)
            .and(ACCOUNT_INVITATION.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))),
        reason,
        now);
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
        .where(scope)
        .and(pending())
        .execute();
  }

  private static Condition pending() {
    return ACCOUNT_INVITATION.STATUS.eq(AccountInvitationStatus.PENDING);
  }
}
