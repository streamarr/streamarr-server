package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.ExternalIdentifier.EXTERNAL_IDENTIFIER;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.row;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.services.pagination.ItemSortValue;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.BasicType;
import org.hibernate.type.spi.TypeConfiguration;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Row2;
import org.jooq.SortField;
import org.jooq.SortOrder;
import org.jooq.Table;
import org.jooq.TableField;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, Query query, Class<E> type) {
    var result = em.createNativeQuery(query.getSQL(), type).unwrap(NativeQuery.class);
    var types =
        em.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class).getTypeConfiguration();

    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      var value = values.get(i);
      if (value instanceof Temporal temporal) {
        result.setParameter(i + 1, temporal, directBindingType(temporal, types));
        continue;
      }

      result.setParameter(i + 1, value);
    }

    return result.getResultList();
  }

  // Hibernate types an untyped parameter by the basic type registered for its Java class, which
  // binds java.time values through java.sql.Date and java.sql.Timestamp regardless of
  // hibernate.type.java_time_use_direct_jdbc. A java.time value binds with the JDBC type an entity
  // attribute of the same class uses.
  private <T extends Temporal> BasicType<T> directBindingType(T value, TypeConfiguration types) {
    @SuppressWarnings("unchecked")
    var valueClass = (Class<T>) value.getClass();
    var javaType = types.getJavaTypeRegistry().resolveDescriptor(valueClass);
    var jdbcType = javaType.getRecommendedJdbcType(types.getCurrentBaseSqlTypeIndicators());
    return types.getBasicTypeRegistry().resolve(javaType, jdbcType);
  }

  public ItemSortValue itemSortValue(Object value) {
    return new ItemSortValue(value instanceof OffsetDateTime time ? time.toInstant() : value);
  }

  public Condition startLetterCondition(
      AlphabetLetter startLetter, SortDirection direction, OrderMediaBy sortBy) {
    if (startLetter == null) {
      return noCondition();
    }

    if (sortBy != OrderMediaBy.TITLE) {
      return equalityLetterCondition(startLetter);
    }

    return direction == SortDirection.DESC
        ? descLetterCondition(startLetter)
        : ascLetterCondition(startLetter);
  }

  // Matches the rows above a TITLE-sort letter jump's landing page - the exact negation of the
  // landing condition. Empty when nothing can sit above the anchor: HASH lands at the top under
  // ASC and Z at the top under DESC.
  public Optional<Condition> letterJumpPredecessorCondition(
      AlphabetLetter startLetter, SortDirection direction) {
    if (direction == SortDirection.DESC) {
      return descLetterJumpPredecessorCondition(startLetter);
    }

    if (startLetter == AlphabetLetter.HASH) {
      return Optional.empty();
    }

    return Optional.of(titleSeekPosition().lt(row(inline(false), letterValue(startLetter))));
  }

  private Optional<Condition> descLetterJumpPredecessorCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.Z) {
      return Optional.empty();
    }

    return Optional.of(titleSeekPosition().ge(descLandingCeiling(startLetter)));
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
    return alphabetLetterField().eq(inline(startLetter.name()));
  }

  private Condition ascLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.HASH) {
      return noCondition();
    }

    return titleSeekPosition().ge(row(inline(false), letterValue(startLetter)));
  }

  private Condition descLetterCondition(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.Z) {
      return noCondition();
    }

    return titleSeekPosition().lt(descLandingCeiling(startLetter));
  }

  // HASH lands on the contiguous titles below "a"; titles above "z" stay at the top of the
  // ordering, reachable by paging backward.
  private Row2<Boolean, String> descLandingCeiling(AlphabetLetter startLetter) {
    if (startLetter == AlphabetLetter.HASH) {
      return row(inline(false), letterValue(AlphabetLetter.A));
    }

    return row(inline(false), nextLetterValue(startLetter));
  }

  public Field<String> alphabetLetterField() {
    var firstChar = left(BASE_COLLECTABLE.TITLE_SORT, inline(1)).collate("C");
    return when(firstChar.likeRegex(inline("[a-zA-Z]")), upper(firstChar))
        .otherwise(inline("HASH"));
  }

  private Field<String> letterValue(AlphabetLetter letter) {
    return inline(letter.name().toLowerCase());
  }

  private Field<String> nextLetterValue(AlphabetLetter startLetter) {
    return letterValue(AlphabetLetter.values()[startLetter.ordinal() + 1]);
  }

  public Condition libraryCondition(UUID libraryId) {
    return libraryId != null ? BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId) : noCondition();
  }

  private Field<String> titleSortField() {
    return normalizeTitle(BASE_COLLECTABLE.TITLE_SORT);
  }

  public Field<String> normalizeTitle(Field<String> title) {
    return lower(title.collate("C"));
  }

  public List<SortField<?>> titleOrderBy(SortDirection direction) {
    var sqlDirection = direction == SortDirection.ASC ? SortOrder.ASC : SortOrder.DESC;
    return List.of(
        field(BASE_COLLECTABLE.TITLE_SORT.isNull()).sort(sqlDirection),
        titleSeekField().sort(sqlDirection),
        BASE_COLLECTABLE.ID.sort(sqlDirection));
  }

  public List<SortField<?>> mediaOrderBy(
      MediaFilter filter, Field<?> sortField, boolean reversePage) {
    var direction = filter.getSortDirection();
    if (filter.getSortBy() == OrderMediaBy.TITLE) {
      return titleOrderBy(direction);
    }

    var sqlDirection = direction == SortDirection.ASC ? SortOrder.ASC : SortOrder.DESC;
    var order = sortField.sort(sqlDirection);
    if (isNullableSortField(filter.getSortBy())) {
      order = reversePage ? order.nullsFirst() : order.nullsLast();
    }

    return List.of(order, BASE_COLLECTABLE.ID.sort(sqlDirection));
  }

  private Field<String> titleSeekField() {
    return coalesce(titleSortField(), inline("")).collate("C");
  }

  private Row2<Boolean, String> titleSeekPosition() {
    return row(field(BASE_COLLECTABLE.TITLE_SORT.isNull()), titleSeekField());
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
            select(EXTERNAL_IDENTIFIER.ENTITY_ID)
                .from(EXTERNAL_IDENTIFIER)
                .where(EXTERNAL_IDENTIFIER.ENTITY_ID.eq(BASE_COLLECTABLE.ID))));
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
    if (filter.getSortDirection().equals(SortDirection.DESC)) {
      return filter.toBuilder().sortDirection(SortDirection.ASC).build();
    }

    return filter.toBuilder().sortDirection(SortDirection.DESC).build();
  }

  public boolean isNullableSortField(OrderMediaBy sortBy) {
    return sortBy == OrderMediaBy.RELEASE_DATE
        || sortBy == OrderMediaBy.RUNTIME
        || sortBy == OrderMediaBy.LAST_WATCHED;
  }

  @SuppressWarnings("unchecked")
  public Condition buildSeekCondition(MediaSeek seek) {
    var filter = seek.filter();
    var sortCol = seek.sortColumn();
    var orderByColumns = seek.orderByColumns();
    var cursorId = seek.cursorId();
    var reversePage = seek.reversePage();
    var idField = BASE_COLLECTABLE.ID;
    var sortValue = filter.getPreviousSortFieldValue();
    var isAsc = filter.getSortDirection() == SortDirection.ASC;

    if (filter.getSortBy() == OrderMediaBy.TITLE) {
      return titleSeekCondition(filter, cursorId);
    }

    if (!isNullableSortField(filter.getSortBy())) {
      var fields = orderByColumns.stream().map(SortField::$field).toList();
      var seekValues = new Object[] {sortValue, cursorId};
      return isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
    }

    var typedCol = (Field<Object>) sortCol;

    if (sortValue != null) {
      var fields = orderByColumns.stream().map(SortField::$field).toList();
      var seekValues = new Object[] {sortValue, cursorId};
      var rowSeek =
          isAsc ? row(fields).greaterOrEqual(seekValues) : row(fields).lessOrEqual(seekValues);
      return reversePage ? rowSeek : rowSeek.or(typedCol.isNull());
    }

    var idSeek = isAsc ? idField.ge(cursorId) : idField.le(cursorId);
    var nullSeek = typedCol.isNull().and(idSeek);
    return reversePage ? nullSeek.or(typedCol.isNotNull()) : nullSeek;
  }

  private Condition titleSeekCondition(MediaFilter filter, UUID cursorId) {
    var value = filter.getPreviousSortFieldValue();
    var isAsc = filter.getSortDirection() == SortDirection.ASC;
    var fields =
        row(field(BASE_COLLECTABLE.TITLE_SORT.isNull()), titleSeekField(), BASE_COLLECTABLE.ID);
    var seekValues =
        row(
            inline(value == null),
            value == null ? inline("") : normalizeTitle(val(titleSortValue(value))),
            val(cursorId));
    return isAsc ? fields.ge(seekValues) : fields.le(seekValues);
  }

  private String titleSortValue(@NonNull Object value) {
    return value.toString();
  }
}
