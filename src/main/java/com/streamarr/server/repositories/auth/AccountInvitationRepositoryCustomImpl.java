package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountInvitation.ACCOUNT_INVITATION;

import com.streamarr.server.domain.auth.AccountInvitationStatus;
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
public class AccountInvitationRepositoryCustomImpl implements AccountInvitationRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  public void lockRecipientForReplacement(String recipientEmail) {
    transactionLocks.lockNormalizedKey("account-invitation", recipientEmail);
  }

  @Override
  public boolean tryDecide(UUID invitationId, AccountInvitationStatus target, Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
            .set(ACCOUNT_INVITATION.STATUS, jooqStatus(target))
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
  public int invalidatePendingForEmail(String recipientEmail, String reason, Instant now) {
    return invalidate(
        ACCOUNT_INVITATION.RECIPIENT_EMAIL.equalIgnoreCase(recipientEmail), reason, now);
  }

  @Override
  public int invalidatePendingForHousehold(UUID householdId, String reason, Instant now) {
    return invalidate(ACCOUNT_INVITATION.HOUSEHOLD_ID.eq(householdId), reason, now);
  }

  @Override
  public int invalidatePendingForProfile(UUID profileId, String reason, Instant now) {
    return invalidate(ACCOUNT_INVITATION.PROFILE_ID.eq(profileId), reason, now);
  }

  @Override
  public int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now) {
    return invalidate(ACCOUNT_INVITATION.ISSUER_ACCOUNT_ID.eq(issuerAccountId), reason, now);
  }

  @Override
  public int sweepExpired(Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
        .set(
            ACCOUNT_INVITATION.STATUS,
            com.streamarr.server.jooq.generated.enums.AccountInvitationStatus.EXPIRED)
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .where(pending())
        .and(ACCOUNT_INVITATION.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  private int invalidate(Condition scope, String reason, Instant now) {
    return dsl.update(ACCOUNT_INVITATION)
        .set(
            ACCOUNT_INVITATION.STATUS,
            com.streamarr.server.jooq.generated.enums.AccountInvitationStatus.INVALIDATED)
        .set(ACCOUNT_INVITATION.INVALIDATION_REASON, reason)
        .set(ACCOUNT_INVITATION.DECIDED_AT, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(ACCOUNT_INVITATION.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(pending())
        .execute();
  }

  private static Condition pending() {
    return ACCOUNT_INVITATION.STATUS.eq(
        com.streamarr.server.jooq.generated.enums.AccountInvitationStatus.PENDING);
  }

  private static com.streamarr.server.jooq.generated.enums.AccountInvitationStatus jooqStatus(
      AccountInvitationStatus status) {
    return com.streamarr.server.jooq.generated.enums.AccountInvitationStatus.valueOf(status.name());
  }
}
