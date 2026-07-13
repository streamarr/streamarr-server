package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.HouseholdMembership;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class HouseholdMembershipRepositoryCustomImpl
    implements HouseholdMembershipRepositoryCustom {

  private final EntityManager entityManager;
  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;

  @Override
  @Transactional
  public void grantMembership(HouseholdMembership membership) {
    entityManager.persist(membership);
    entityManager.flush();
  }

  @Override
  @Transactional
  public boolean changeRole(HouseholdMembership membership) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);

    return dsl.update(HOUSEHOLD_MEMBERSHIP)
            .set(
                HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ROLE,
                com.streamarr.server.jooq.generated.enums.HouseholdRole.valueOf(
                    membership.getHouseholdRole().name()))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_ON, OffsetDateTime.now(ZoneOffset.UTC))
            .set(HOUSEHOLD_MEMBERSHIP.LAST_MODIFIED_BY, auditUser)
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(membership.getAccountId()))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(membership.getHouseholdId()))
            .execute()
        > 0;
  }

  @Override
  @Transactional
  public boolean revokeMembership(UUID accountId, UUID householdId) {
    return dsl.deleteFrom(HOUSEHOLD_MEMBERSHIP)
            .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
            .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
            .execute()
        > 0;
  }
}
