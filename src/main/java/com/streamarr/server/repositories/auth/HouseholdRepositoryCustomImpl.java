package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.Household.HOUSEHOLD;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SortOrder;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class HouseholdRepositoryCustomImpl implements HouseholdRepositoryCustom {

  private final DSLContext dsl;
  private final EntityManager entityManager;
  private final AuditorAware<UUID> auditorAware;
  private final Clock clock;

  @Override
  public void refresh(Household household) {
    entityManager.refresh(household);
  }

  @Override
  public List<Household> findAdministrationPage(MediaPaginationOptions options) {
    var reverse =
        options.getPaginationOptions().getPaginationDirection() == PaginationDirection.REVERSE;
    var sortOrder = reverse ? SortOrder.DESC : SortOrder.ASC;
    var name = lower(HOUSEHOLD.NAME);
    var cursorCondition = cursorCondition(options, name, sortOrder);
    var cursorFirst =
        options
            .getCursorId()
            .map(cursorId -> when(HOUSEHOLD.ID.eq(cursorId), 0).otherwise(1))
            .orElse(val(0));
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select(HOUSEHOLD.asterisk())
            .from(HOUSEHOLD)
            .where(cursorCondition)
            .orderBy(cursorFirst.asc(), name.sort(sortOrder), HOUSEHOLD.ID.sort(sortOrder))
            .limit(options.getPaginationOptions().getLimit() + extraRows);
    var households = JooqQueryHelper.nativeQuery(entityManager, query, Household.class);
    if (reverse) {
      Collections.reverse(households);
    }
    return households;
  }

  private Condition cursorCondition(
      MediaPaginationOptions options, Field<String> name, SortOrder sortOrder) {
    if (options.getCursorId().isEmpty()) {
      return noCondition();
    }
    var cursorName = lower(val(options.getMediaFilter().getPreviousSortFieldValue().toString()));
    var cursorId = options.getCursorId().orElseThrow();
    var fields = row(name, HOUSEHOLD.ID);
    var cursor = row(cursorName, val(cursorId));
    var afterCursor =
        sortOrder == SortOrder.ASC ? fields.greaterThan(cursor) : fields.lessThan(cursor);
    return HOUSEHOLD.ID.eq(cursorId).or(HOUSEHOLD.ID.ne(cursorId).and(afterCursor));
  }

  @Override
  public boolean tryRename(UUID householdId, String name) {
    return dsl.update(HOUSEHOLD)
            .set(HOUSEHOLD.NAME, name)
            .set(HOUSEHOLD.LAST_MODIFIED_ON, clock.instant().atOffset(ZoneOffset.UTC))
            .set(HOUSEHOLD.LAST_MODIFIED_BY, auditorAware.getCurrentAuditor().orElse(null))
            .where(HOUSEHOLD.ID.eq(householdId))
            .execute()
        > 0;
  }
}
