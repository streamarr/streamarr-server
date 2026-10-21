package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.jooq.generated.enums.ProfileShareStatus;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class HouseholdRepositoryCustomImpl implements HouseholdRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;

  @Override
  public void refresh(Household household) {
    entityManager.refresh(household);
  }

  @Override
  public List<Row<Household>> rowsBeyond(Seek seek) {
    var keyset =
        Keyset.<String>builder().sortValue(lower(HOUSEHOLD.NAME)).itemId(HOUSEHOLD.ID).build();
    var query =
        keyset.rowsBeyond(
            dsl.select(HOUSEHOLD.asterisk(), keyset.selectedSortValue()).from(HOUSEHOLD), seek);
    return JooqQueryHelper.nativeRows(entityManager, query, Household.class, keyset);
  }

  @Override
  public Set<UUID> lockByIds(Set<UUID> householdIds) {
    return dsl.select(HOUSEHOLD.ID)
        .from(HOUSEHOLD)
        .where(HOUSEHOLD.ID.in(householdIds))
        .orderBy(HOUSEHOLD.ID)
        .forUpdate()
        .fetchSet(HOUSEHOLD.ID);
  }

  @Override
  public boolean lockById(UUID householdId) {
    return dsl.select(HOUSEHOLD.ID)
        .from(HOUSEHOLD)
        .where(HOUSEHOLD.ID.eq(householdId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  public boolean tryRename(UUID householdId, String name) {
    return dsl.update(HOUSEHOLD)
            .set(HOUSEHOLD.NAME, name)
            .where(HOUSEHOLD.ID.eq(householdId))
            .execute()
        > 0;
  }

  // Household names are never blank, so the Account's own Household sorts before every other.
  @Override
  public List<Row<Household>> rowsUsableBy(UUID accountId, Seek seek) {
    var keyset =
        Keyset.<String>builder()
            .sortValue(
                when(HOUSEHOLD.ID.eq(USER_ACCOUNT.HOUSEHOLD_ID), inline(""))
                    .otherwise(lower(HOUSEHOLD.NAME)))
            .itemId(HOUSEHOLD.ID)
            .build();
    var query =
        keyset.rowsBeyond(
            dsl.select(HOUSEHOLD.asterisk(), keyset.selectedSortValue())
                .from(HOUSEHOLD)
                .join(PROFILE_HOUSEHOLD_SHARE)
                .on(PROFILE_HOUSEHOLD_SHARE.HOUSEHOLD_ID.eq(HOUSEHOLD.ID))
                .join(USER_ACCOUNT)
                .on(USER_ACCOUNT.PERSONAL_PROFILE_ID.eq(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID)),
            USER_ACCOUNT
                .ID
                .eq(accountId)
                .and(PROFILE_HOUSEHOLD_SHARE.STATUS.eq(inline(ProfileShareStatus.ACTIVE))),
            seek);
    return JooqQueryHelper.nativeRows(entityManager, query, Household.class, keyset);
  }
}
