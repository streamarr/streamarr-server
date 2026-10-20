package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;
import static com.streamarr.server.jooq.generated.tables.ExternalIdentifier.EXTERNAL_IDENTIFIER;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.not;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortValue;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.NativeQuery;
import org.hibernate.type.BasicType;
import org.hibernate.type.spi.TypeConfiguration;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.TableField;

@UtilityClass
public class JooqQueryHelper {

  @SuppressWarnings("unchecked")
  public <E> List<E> nativeQuery(EntityManager em, Query query, Class<E> type) {
    NativeQuery<E> result = em.createNativeQuery(query.getSQL(), type).unwrap(NativeQuery.class);
    bindParameters(em, result, query);
    return result.getResultList();
  }

  /**
   * Reads entities and the keyset sort value {@code query} selects beside their columns as {@link
   * Keyset#selectedSortValue()}, in query order.
   */
  @SuppressWarnings("unchecked")
  public <E extends BaseAuditableEntity<?>> List<Row<E>> nativeRows(
      EntityManager em, Query query, Class<E> type, Keyset<?> keyset) {
    NativeQuery<Object[]> result = em.createNativeQuery(query.getSQL()).unwrap(NativeQuery.class);
    result.addEntity(type);
    result.addScalar(
        Keyset.SELECTED_SORT_VALUE, directJdbcType(keyset.sortValue().getType(), typesOf(em)));
    bindParameters(em, result, query);
    return result.getResultList().stream()
        .map(
            columns -> {
              var entity = type.cast(columns[0]);
              return new Row<>(entity, new Position(SortValue.of(columns[1]), entity.getId()));
            })
        .toList();
  }

  private void bindParameters(EntityManager em, NativeQuery<?> result, Query query) {
    var types = typesOf(em);
    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      var value = values.get(i);
      if (value instanceof Temporal temporal) {
        result.setParameter(i + 1, temporal, directJdbcType(classOf(temporal), types));
        continue;
      }

      result.setParameter(i + 1, value);
    }
  }

  private TypeConfiguration typesOf(EntityManager em) {
    return em.getEntityManagerFactory()
        .unwrap(SessionFactoryImplementor.class)
        .getTypeConfiguration();
  }

  @SuppressWarnings("unchecked")
  private <T extends Temporal> Class<T> classOf(T value) {
    return (Class<T>) value.getClass();
  }

  // Hibernate types an untyped parameter or scalar by the basic type registered for its Java
  // class, which binds and reads java.time values through java.sql.Date and java.sql.Timestamp
  // regardless of hibernate.type.java_time_use_direct_jdbc. A value of a Java class binds and reads
  // with the JDBC type an entity attribute of the same class uses.
  private <T> BasicType<T> directJdbcType(Class<T> javaClass, TypeConfiguration types) {
    var javaType = types.getJavaTypeRegistry().resolveDescriptor(javaClass);
    var jdbcType = javaType.getRecommendedJdbcType(types.getCurrentBaseSqlTypeIndicators());
    return types.getBasicTypeRegistry().resolve(javaType, jdbcType);
  }

  /**
   * Keeps the items whose title starts with {@code startLetter}, except under TITLE sort, where the
   * letter only chooses where the first page lands.
   */
  public Condition startLetterFilterCondition(AlphabetLetter startLetter, OrderMediaBy sortBy) {
    if (startLetter == null || sortBy == OrderMediaBy.TITLE) {
      return noCondition();
    }
    return equalityLetterCondition(startLetter);
  }

  private Condition equalityLetterCondition(AlphabetLetter startLetter) {
    return alphabetLetterField().eq(inline(startLetter.name()));
  }

  public Field<String> alphabetLetterField() {
    var firstChar = left(BASE_COLLECTABLE.TITLE_SORT, inline(1)).collate("C");
    return when(firstChar.likeRegex(inline("[a-zA-Z]")), upper(firstChar))
        .otherwise(inline("HASH"));
  }

  public Condition libraryCondition(UUID libraryId) {
    return libraryId != null ? BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId) : noCondition();
  }

  /** The title an item sorts by, compared byte by byte so every locale orders it alike. */
  public Field<String> titleSortField() {
    return normalizeTitle(BASE_COLLECTABLE.TITLE_SORT);
  }

  public Field<String> normalizeTitle(Field<String> title) {
    return lower(title.collate("C"));
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
}
