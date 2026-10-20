package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static org.jooq.impl.DSL.lower;

import com.streamarr.server.domain.auth.Household;
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
}
