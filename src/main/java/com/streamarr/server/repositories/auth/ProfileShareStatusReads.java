package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import java.time.OffsetDateTime;
import org.jooq.Condition;
import org.jooq.Field;

/**
 * How a share's stored status reads at a time: a pending offer whose time ran out reads as expired
 * before the expiry sweep stores it.
 */
public final class ProfileShareStatusReads {

  private ProfileShareStatusReads() {}

  public static Condition readsAs(ProfileShareStatus status, Field<OffsetDateTime> at) {
    var stored = PROFILE_HOUSEHOLD_SHARE.STATUS.eq(inline(status));
    return switch (status) {
      case PENDING -> stored.and(unexpiredAt(at));
      case EXPIRED ->
          stored.or(
              PROFILE_HOUSEHOLD_SHARE
                  .STATUS
                  .eq(inline(ProfileShareStatus.PENDING))
                  .and(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.le(at)));
      case ACTIVE, REJECTED, CANCELED, INVALIDATED, ENDED -> stored;
    };
  }

  /** Expiry is a predicate over expires_at; a NULL expiry never expires. */
  static Condition unexpiredAt(Field<OffsetDateTime> now) {
    return PROFILE_HOUSEHOLD_SHARE
        .EXPIRES_AT
        .isNull()
        .or(PROFILE_HOUSEHOLD_SHARE.EXPIRES_AT.gt(now));
  }
}
