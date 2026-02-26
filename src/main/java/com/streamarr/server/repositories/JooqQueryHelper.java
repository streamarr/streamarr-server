package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.jooq.generated.Tables;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;
import org.jooq.Field;
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
      return firstCharLower.lessThan(inline("a"));
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
      return firstCharLower.lessThan(inline("a"));
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
}
