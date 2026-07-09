package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.AccountProfile.ACCOUNT_PROFILE;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.AccountProfile;
import com.streamarr.server.services.auth.CounterKind;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import com.streamarr.server.services.auth.invalidation.CounterNotificationPayload;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class AccountProfileRepositoryCustomImpl implements AccountProfileRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional
  public void linkProfile(AccountProfile link) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    dsl.insertInto(ACCOUNT_PROFILE)
        .set(ACCOUNT_PROFILE.ACCOUNT_ID, link.getAccountId())
        .set(ACCOUNT_PROFILE.HOUSEHOLD_ID, link.getHouseholdId())
        .set(ACCOUNT_PROFILE.PROFILE_ID, link.getProfileId())
        .set(ACCOUNT_PROFILE.CREATED_BY, auditUser)
        .set(ACCOUNT_PROFILE.LAST_MODIFIED_BY, auditUser)
        .execute();

    bumpMembershipVersion(link, auditUser);
  }

  @Override
  @Transactional
  public boolean revokeProfileLink(AccountProfile link) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    var rowsAffected =
        dsl.deleteFrom(ACCOUNT_PROFILE)
            .where(ACCOUNT_PROFILE.ACCOUNT_ID.eq(link.getAccountId()))
            .and(ACCOUNT_PROFILE.HOUSEHOLD_ID.eq(link.getHouseholdId()))
            .and(ACCOUNT_PROFILE.PROFILE_ID.eq(link.getProfileId()))
            .execute();

    if (rowsAffected == 0) {
      return false;
    }

    bumpMembershipVersion(link, auditUser);
    return true;
  }

  /**
   * The bump, its local cache event, and the cross-instance notify live here so no future
   * grant/revoke endpoint can forget any leg of the invariant (ADR 0015 requirement, ADR 0016
   * propagation).
   */
  private void bumpMembershipVersion(AccountProfile link, UUID auditUser) {
    // The membership row is FK-guaranteed: every account_profile row references it, so the
    // link being granted or revoked proves it exists in this transaction.
    var version =
        dsl.update(HOUSEHOLD_MEMBERSHIP)
            .set(
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION,
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION.plus(1))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_BY, auditUser)
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(link.getAccountId()))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(link.getHouseholdId()))
            .returning(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)
            .fetchSingle()
            .get(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION);

    eventPublisher.publishEvent(
        CounterBumpedEvent.membership(link.getAccountId(), link.getHouseholdId(), version));
    CounterNotificationPublisher.publish(
        dsl,
        new CounterNotificationPayload(
            CounterKind.MEMBERSHIP,
            CounterBumpedEvent.membershipKey(link.getAccountId(), link.getHouseholdId()),
            version));
  }
}
