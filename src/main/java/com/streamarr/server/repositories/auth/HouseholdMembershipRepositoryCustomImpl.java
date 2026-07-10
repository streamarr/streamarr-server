package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.Sequences.HOUSEHOLD_MEMBERSHIP_VERSION_SEQ;
import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.MembershipVersionChange;
import com.streamarr.server.jooq.generated.tables.records.HouseholdMembershipRecord;
import com.streamarr.server.services.auth.events.CounterBumpedEvent;
import com.streamarr.server.services.auth.invalidation.CounterNotificationPayload;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class HouseholdMembershipRepositoryCustomImpl
    implements HouseholdMembershipRepositoryCustom {

  private final EntityManager entityManager;
  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final ApplicationEventPublisher eventPublisher;

  @Override
  @Transactional
  public MembershipVersionChange grantMembership(HouseholdMembership membership) {
    membership.setMembershipVersion(nextMembershipVersion());
    entityManager.persist(membership);
    entityManager.flush();
    var versionChange = changeFrom(membership);
    publishVersionChange(versionChange);
    return versionChange;
  }

  @Override
  @Transactional
  public Optional<MembershipVersionChange> changeRole(HouseholdMembership membership) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    var versionChange =
        dsl.update(HOUSEHOLD_MEMBERSHIP)
            .set(
                HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ROLE,
                com.streamarr.server.jooq.generated.enums.HouseholdRole.valueOf(
                    membership.getHouseholdRole().name()))
            .set(
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION, HOUSEHOLD_MEMBERSHIP_VERSION_SEQ.nextval())
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_BY, auditUser)
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(membership.getAccountId()))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(membership.getHouseholdId()))
            .returning(
                HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID,
                HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID,
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)
            .fetchOptional(this::changeFrom);

    versionChange.ifPresent(this::publishVersionChange);
    return versionChange;
  }

  @Override
  @Transactional
  public Optional<MembershipVersionChange> revokeMembership(UUID accountId, UUID householdId) {
    var versionChange =
        dsl.update(HOUSEHOLD_MEMBERSHIP)
            .set(
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION, HOUSEHOLD_MEMBERSHIP_VERSION_SEQ.nextval())
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
            .returning(
                HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID,
                HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID,
                HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)
            .fetchOptional(this::changeFrom);

    if (versionChange.isEmpty()) {
      return Optional.empty();
    }

    var changed = versionChange.orElseThrow();
    dsl.deleteFrom(HOUSEHOLD_MEMBERSHIP)
        .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
        .execute();
    publishVersionChange(changed);
    return versionChange;
  }

  private MembershipVersionChange changeFrom(HouseholdMembership membership) {
    return new MembershipVersionChange(
        membership.getAccountId(), membership.getHouseholdId(), membership.getMembershipVersion());
  }

  private MembershipVersionChange changeFrom(HouseholdMembershipRecord record) {
    return new MembershipVersionChange(
        record.getAccountId(), record.getHouseholdId(), record.getMembershipVersion());
  }

  private long nextMembershipVersion() {
    return dsl.select(HOUSEHOLD_MEMBERSHIP_VERSION_SEQ.nextval()).fetchSingle().value1();
  }

  private void publishVersionChange(MembershipVersionChange versionChange) {
    var event =
        CounterBumpedEvent.membership(
            versionChange.accountId(), versionChange.householdId(), versionChange.version());
    eventPublisher.publishEvent(event);
    CounterNotificationPublisher.publish(
        dsl, new CounterNotificationPayload(event.kind(), event.key(), event.version()));
  }
}
