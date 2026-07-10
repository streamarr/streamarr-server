package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.HouseholdMembership.HOUSEHOLD_MEMBERSHIP;

import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.MembershipVersionChange;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class HouseholdMembershipRepositoryCustomImpl
    implements HouseholdMembershipRepositoryCustom {

  private final EntityManager entityManager;
  private final DSLContext dsl;

  @Override
  @Transactional
  public MembershipVersionChange grantMembership(HouseholdMembership membership) {
    entityManager.persist(membership);
    entityManager.flush();
    return changeFrom(membership);
  }

  @Override
  @Transactional
  public Optional<MembershipVersionChange> revokeMembership(UUID accountId, UUID householdId) {
    return dsl.deleteFrom(HOUSEHOLD_MEMBERSHIP)
        .where(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID.eq(accountId))
        .and(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID.eq(householdId))
        .returning(
            HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID,
            HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID,
            HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)
        .fetchOptional(
            record ->
                new MembershipVersionChange(
                    record.get(HOUSEHOLD_MEMBERSHIP.ACCOUNT_ID),
                    record.get(HOUSEHOLD_MEMBERSHIP.HOUSEHOLD_ID),
                    record.get(HOUSEHOLD_MEMBERSHIP.MEMBERSHIP_VERSION)));
  }

  private MembershipVersionChange changeFrom(HouseholdMembership membership) {
    return new MembershipVersionChange(
        membership.getAccountId(), membership.getHouseholdId(), membership.getMembershipVersion());
  }
}
