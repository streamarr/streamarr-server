package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.jooq.generated.Tables;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.TableField;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, org.jooq.Query query, Class<E> type) {
    var result = em.createNativeQuery(query.getSQL(), type);

    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }

    return result.getResultList();
  }

  public Condition startLetterCondition(
      AlphabetLetter startLetter, SortOrder direction, OrderMediaBy sortBy) {
    if (startLetter == null) {
      return noCondition();
    }

    if (sortBy != OrderMediaBy.TITLE) {
      return equalityLetterCondition(startLetter);
    }

    return direction == SortOrder.DESC
        ? descLetterCondition(startLetter)
        : ascLetterCondition(startLetter);
  }

  private Condition equalityLetterCondition(AlphabetLetter startLetter) {
    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));

    if (startLetter == AlphabetLetter.HASH) {
      return firstCharLower
          .lessThan(inline("a"))
          .or(firstCharLower.greaterThan(inline("z")));
    }

    return firstCharLower.eq(inline(startLetter.name().toLowerCase()));
  }

  private Condition ascLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.HASH) {
      return noCondition();
    }

    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));
    return firstCharLower.greaterOrEqual(inline(startLetter.name().toLowerCase()));
  }

  private Condition descLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.Z) {
      return noCondition();
    }

    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));

    if (startLetter == AlphabetLetter.HASH) {
      return firstCharLower
          .lessThan(inline("a"))
          .or(firstCharLower.greaterThan(inline("z")));
    }

    return firstCharLower.lessOrEqual(inline(startLetter.name().toLowerCase()));
  }

  public Condition libraryCondition(UUID libraryId) {
    return libraryId != null ? Tables.BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId) : noCondition();
  }

  public Condition unmatchedCondition(Boolean unmatched) {
    if (!Boolean.TRUE.equals(unmatched)) {
      return noCondition();
    }
    return not(
        Tables.BASE_COLLECTABLE.ID.in(
            select(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID).from(Tables.EXTERNAL_IDENTIFIER)));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public <R extends org.jooq.Record> Condition semiJoinCondition(
      Field<UUID> entityIdField,
      Table<R> joinTable,
      TableField<R, UUID> joinEntityIdField,
      TableField<R, UUID> joinFilterIdField,
      Collection<UUID> filterIds) {
    if (filterIds == null || filterIds.isEmpty()) {
      return noCondition();
    }
    return entityIdField.in(
        select(joinEntityIdField).from(joinTable).where(joinFilterIdField.in(filterIds)));
  }

  public MediaFilter reverseFilter(MediaFilter filter) {
    if (filter.getSortDirection().equals(SortOrder.DESC)) {
      return filter.toBuilder().sortDirection(SortOrder.ASC).build();
    }

    return filter.toBuilder().sortDirection(SortOrder.DESC).build();
  }

  public boolean isNullableSortField(OrderMediaBy sortBy) {
    return sortBy == OrderMediaBy.RELEASE_DATE || sortBy == OrderMediaBy.RUNTIME;
  }

  public Object coerceSortValue(MediaFilter filter) {
    var value = filter.getPreviousSortFieldValue();
    if (value == null) {
      return null;
    }
    return switch (filter.getSortBy()) {
      case RELEASE_DATE -> value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
      case RUNTIME -> value instanceof Integer i ? i : Integer.parseInt(value.toString());
      default -> value;
    };
  }

  @SuppressWarnings("unchecked")
  public Condition buildSeekCondition(
      MediaFilter filter,
      Field<?> sortCol,
      SortField<?>[] orderByColumns,
      Optional<UUID> cursorId) {
    var idField = Tables.BASE_COLLECTABLE.ID;
    var coercedValue = coerceSortValue(filter);
    var cursorIdValue = cursorId.orElse(null);
    var isAsc = filter.getSortDirection() == SortOrder.ASC;

    if (!isNullableSortField(filter.getSortBy()) || coercedValue != null) {
      var fields = Arrays.stream(orderByColumns).map(SortField::$field).toList();
      var seekValues = new Object[] {coercedValue, cursorIdValue};
      return isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
    }

    var typedCol = (Field<Object>) sortCol;
    if (isAsc) {
      return typedCol.isNull().and(idField.greaterOrEqual(cursorIdValue));
    }
    return typedCol.isNull().and(idField.lessOrEqual(cursorIdValue));
  }
}
