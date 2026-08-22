package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.PasswordResetCode.PASSWORD_RESET_CODE;

import com.streamarr.server.jooq.generated.enums.PasswordResetCodeStatus;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class PasswordResetCodeRepositoryCustomImpl implements PasswordResetCodeRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final PostgresTransactionLocks transactionLocks;

  @Override
  public void lockAccountForReplacement(UUID accountId) {
    transactionLocks.lockNormalizedKey("password-reset", accountId.toString());
  }

  @Override
  public boolean tryRedeem(UUID codeId, Instant now) {
    return dsl.update(PASSWORD_RESET_CODE)
            .set(PASSWORD_RESET_CODE.STATUS, PasswordResetCodeStatus.REDEEMED)
            .set(PASSWORD_RESET_CODE.REDEEMED_AT, now.atOffset(ZoneOffset.UTC))
            .set(PASSWORD_RESET_CODE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
            .set(
                PASSWORD_RESET_CODE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(PASSWORD_RESET_CODE.ID.eq(codeId))
            .and(pending())
            .and(PASSWORD_RESET_CODE.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC)))
            .execute()
        > 0;
  }

  @Override
  public int invalidatePendingForAccount(UUID accountId, String reason, Instant now) {
    return invalidate(PASSWORD_RESET_CODE.ACCOUNT_ID.eq(accountId), reason, now);
  }

  @Override
  public int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now) {
    return invalidate(PASSWORD_RESET_CODE.ISSUER_ACCOUNT_ID.eq(issuerAccountId), reason, now);
  }

  @Override
  public int sweepExpired(Instant now) {
    return dsl.update(PASSWORD_RESET_CODE)
        .set(PASSWORD_RESET_CODE.STATUS, PasswordResetCodeStatus.EXPIRED)
        .set(PASSWORD_RESET_CODE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .where(pending())
        .and(PASSWORD_RESET_CODE.EXPIRES_AT.le(now.atOffset(ZoneOffset.UTC)))
        .execute();
  }

  private int invalidate(Condition scope, String reason, Instant now) {
    return dsl.update(PASSWORD_RESET_CODE)
        .set(PASSWORD_RESET_CODE.STATUS, PasswordResetCodeStatus.INVALIDATED)
        .set(PASSWORD_RESET_CODE.INVALIDATION_REASON, reason)
        .set(PASSWORD_RESET_CODE.LAST_MODIFIED_ON, now.atOffset(ZoneOffset.UTC))
        .set(PASSWORD_RESET_CODE.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
        .where(scope)
        .and(pending())
        .execute();
  }

  private static Condition pending() {
    return PASSWORD_RESET_CODE.STATUS.eq(PasswordResetCodeStatus.PENDING);
  }
}
