package com.streamarr.server.repositories.auth;

import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;

@UtilityClass
class AuditableEntityPageQuery {

  static <R extends Record, E> List<E> findPage(
      DSLContext dsl,
      EntityManager entityManager,
      Table<R> table,
      Field<OffsetDateTime> createdOn,
      Field<UUID> id,
      Condition scope,
      KeysetPaginationOptions options,
      Class<E> entityType) {
    var pagination = options.getPaginationOptions();
    var reverse = pagination.getPaginationDirection() == PaginationDirection.REVERSE;
    var cursorCondition = cursorCondition(dsl, table, createdOn, id, scope, options, reverse);
    var extraRows = options.getCursorId().isPresent() ? 2 : 1;
    var query =
        dsl.select()
            .from(table)
            .where(scope)
            .and(cursorCondition)
            .orderBy(
                reverse ? createdOn.asc().nullsLast() : createdOn.desc().nullsLast(),
                reverse ? id.desc() : id.asc())
            .limit(pagination.getLimit() + extraRows);
    var found = JooqQueryHelper.nativeQuery(entityManager, query, entityType);
    if (reverse) {
      Collections.reverse(found);
    }

    return found;
  }

  private static <R extends Record> Condition cursorCondition(
      DSLContext dsl,
      Table<R> table,
      Field<OffsetDateTime> createdOn,
      Field<UUID> id,
      Condition scope,
      KeysetPaginationOptions options,
      boolean reverse) {
    return options
        .getCursorId()
        .<Condition>map(
            cursorId -> cursorCondition(dsl, table, createdOn, id, scope, cursorId, reverse))
        .orElseGet(DSL::noCondition);
  }

  private static <R extends Record> Condition cursorCondition(
      DSLContext dsl,
      Table<R> table,
      Field<OffsetDateTime> createdOn,
      Field<UUID> id,
      Condition scope,
      UUID cursorId,
      boolean reverse) {
    var cursor =
        dsl.select(createdOn, id)
            .from(table)
            .where(scope)
            .and(id.eq(cursorId))
            .fetchOptional(row -> new Cursor(row.value1(), row.value2()))
            .orElseThrow(
                () -> new InvalidPaginationCursorException("Cursor no longer identifies an item."));
    var sameCreatedOn = createdOn.eq(cursor.createdOn());
    if (reverse) {
      return createdOn.gt(cursor.createdOn()).or(sameCreatedOn.and(id.le(cursor.id())));
    }

    return createdOn.lt(cursor.createdOn()).or(sameCreatedOn.and(id.ge(cursor.id())));
  }

  private record Cursor(OffsetDateTime createdOn, UUID id) {}
}
