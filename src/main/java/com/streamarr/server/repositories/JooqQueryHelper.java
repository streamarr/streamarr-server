package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.TableField;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, Query query, Class<E> type) {
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

  // Matches the rows above a TITLE-sort letter jump's landing page - the exact negation of the
  // landing condition. Empty when nothing can sit above the anchor: HASH lands at the top under
  // ASC, Z at the top under DESC, and DESC HASH matches two non-adjacent runs of the ordering so
  // it has no single anchor to sit below.
  public Optional<Condition> letterJumpPredecessorCondition(
      AlphabetLetter startLetter, SortOrder direction) {
    if (startLetter == AlphabetLetter.HASH) {
      return Optional.empty();
    }

    if (direction == SortOrder.DESC) {
      return startLetter == AlphabetLetter.Z
          ? Optional.empty()
          : Optional.of(titleSortField().greaterOrEqual(nextLetterValue(startLetter)));
    }

    return Optional.of(titleSortField().lessThan(letterValue(startLetter)));
  }

  // Under TITLE sort the letter is a seek anchor consumed by the landing page - on cursor pages
  // the keyset alone fixes the position, and re-applying the letter would wall off backward
  // pages. Non-TITLE sorts keep the letter as an equality restriction on every page.
  public Condition startLetterCursorPageCondition(AlphabetLetter startLetter, OrderMediaBy sortBy) {
    if (startLetter == null || sortBy == OrderMediaBy.TITLE) {
      return noCondition();
    }
    return equalityLetterCondition(startLetter);
  }

  private Condition equalityLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.HASH) {
      return hashLetterCondition();
    }

    var titleSort = titleSortField();
    if (startLetter == AlphabetLetter.Z) {
      return titleSort
          .greaterOrEqual(letterValue(startLetter))
          .and(lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1)).eq(letterValue(startLetter)));
    }

    return titleSort
        .greaterOrEqual(letterValue(startLetter))
        .and(titleSort.lessThan(nextLetterValue(startLetter)));
  }

  private Condition ascLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.HASH) {
      return noCondition();
    }

    return titleSortField().greaterOrEqual(letterValue(startLetter));
  }

  private Condition descLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.Z) {
      return noCondition();
    }

    if (startLetter == AlphabetLetter.HASH) {
      return hashLetterCondition();
    }

    return titleSortField().lessThan(nextLetterValue(startLetter));
  }

  private Condition hashLetterCondition() {
    var firstCharLower = lower(left(Tables.BASE_COLLECTABLE.TITLE_SORT, 1));
    return firstCharLower.lessThan(inline("a")).or(firstCharLower.greaterThan(inline("z")));
  }

  private Field<String> letterValue(AlphabetLetter letter) {
    return inline(letter.name().toLowerCase());
  }

  private Field<String> nextLetterValue(AlphabetLetter startLetter) {
    return letterValue(AlphabetLetter.values()[startLetter.ordinal() + 1]);
  }

  public Condition libraryCondition(UUID libraryId) {
    return libraryId != null ? Tables.BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId) : noCondition();
  }

  public Field<String> titleSortField() {
    return lower(Tables.BASE_COLLECTABLE.TITLE_SORT);
  }

  public Condition yearCondition(Field<LocalDate> dateField, List<Integer> years) {
    if (years == null || years.isEmpty()) {
      return noCondition();
    }
    return years.stream()
        .map(year -> dateField.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)))
        .reduce(Condition::or)
        .orElse(noCondition());
  }

  public Condition contentRatingCondition(Field<String> ratingField, List<String> ratings) {
    if (ratings == null || ratings.isEmpty()) {
      return noCondition();
    }
    return ratingField.in(ratings);
  }

  public Condition unmatchedCondition(Boolean unmatched) {
    if (!Boolean.TRUE.equals(unmatched)) {
      return noCondition();
    }
    return not(
        exists(
            select(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID)
                .from(Tables.EXTERNAL_IDENTIFIER)
                .where(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID.eq(Tables.BASE_COLLECTABLE.ID))));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public <R extends Record> Condition semiJoinCondition(
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
    return sortBy == OrderMediaBy.RELEASE_DATE
        || sortBy == OrderMediaBy.RUNTIME
        || sortBy == OrderMediaBy.LAST_WATCHED;
  }

  public Object coerceSortValue(MediaFilter filter) {
    var value = filter.getPreviousSortFieldValue();
    if (value == null) {
      return null;
    }
    return switch (filter.getSortBy()) {
      case RELEASE_DATE -> value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
      case RUNTIME -> value instanceof Integer i ? i : Integer.parseInt(value.toString());
      case LAST_WATCHED -> value instanceof Instant i ? i : Instant.parse(value.toString());
      default -> value;
    };
  }

  @SuppressWarnings("unchecked")
  public Condition buildSeekCondition(
      MediaFilter filter, Field<?> sortCol, SortField<?>[] orderByColumns, UUID cursorId) {
    var idField = Tables.BASE_COLLECTABLE.ID;
    var coercedValue = coerceSortValue(filter);
    var isAsc = filter.getSortDirection() == SortOrder.ASC;

    if (filter.getSortBy() == OrderMediaBy.TITLE) {
      var titleSortCol = (Field<String>) sortCol;
      var cursorTitleSortValue = titleSortValue(coercedValue);
      var cursorTitleSort = lower(val(cursorTitleSortValue));
      var fields = row(titleSortCol, idField);
      var seekValues = row(cursorTitleSort, val(cursorId));
      return isAsc ? fields.greaterOrEqual(seekValues) : fields.lessOrEqual(seekValues);
    }

    if (!isNullableSortField(filter.getSortBy())) {
      var fields = Arrays.stream(orderByColumns).map(SortField::$field).toList();
      var seekValues = new Object[] {coercedValue, cursorId};
      return isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
    }

    var typedCol = (Field<Object>) sortCol;

    if (coercedValue != null) {
      var fields = Arrays.stream(orderByColumns).map(SortField::$field).toList();
      var seekValues = new Object[] {coercedValue, cursorId};
      var rowSeek =
          isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
      // NULLS LAST: null rows come after all non-null rows regardless of sort direction
      return rowSeek.or(typedCol.isNull());
    }

    if (isAsc) {
      return typedCol.isNull().and(idField.greaterOrEqual(cursorId));
    }
    return typedCol.isNull().and(idField.lessOrEqual(cursorId));
  }

  private String titleSortValue(@NonNull Object value) {
    return value.toString();
  }
}
