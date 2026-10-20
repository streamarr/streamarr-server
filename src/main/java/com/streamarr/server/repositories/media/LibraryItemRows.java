package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.BaseCollectable.BASE_COLLECTABLE;

import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.repositories.JooqQueryHelper;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectFieldOrAsterisk;
import org.jooq.SelectWhereStep;
import org.jooq.Table;

/** Reads a Library's movies or series, each with its position in the list's order. */
@Builder
record LibraryItemRows<E extends BaseCollectable<E>>(
    @NonNull DSLContext dsl,
    @NonNull EntityManager entityManager,
    @NonNull Table<?> kindTable,
    @NonNull Field<UUID> kindId,
    @NonNull Class<E> type) {

  /** The items {@code scope} admits beyond the seek, ordered by {@code sortValue}, then ID. */
  List<Row<E>> beyond(Field<?> sortValue, Condition scope, Seek seek) {
    var keyset = keysetOf(sortValue);
    var query = keyset.rowsBeyond(select(keyset), scope, seek);
    return JooqQueryHelper.nativeRows(entityManager, query, type, keyset);
  }

  /** The item's row, when {@code scope} admits it. */
  Optional<Row<E>> of(UUID itemId, Field<?> sortValue, Condition scope) {
    var keyset = keysetOf(sortValue);
    var query = select(keyset).where(scope).and(BASE_COLLECTABLE.ID.eq(itemId));
    return JooqQueryHelper.nativeRows(entityManager, query, type, keyset).stream().findFirst();
  }

  // Entity rows mapped beside a scalar must not repeat a column name, so the kind table's ID, which
  // the join makes equal to the base row's, is left out.
  private SelectWhereStep<Record> select(Keyset<?> keyset) {
    var columns = new ArrayList<SelectFieldOrAsterisk>();
    Arrays.stream(kindTable.fields()).filter(field -> !field.equals(kindId)).forEach(columns::add);
    columns.add(BASE_COLLECTABLE.asterisk());
    columns.add(keyset.selectedSortValue());
    return dsl.select(columns)
        .from(kindTable)
        .innerJoin(BASE_COLLECTABLE)
        .on(kindId.eq(BASE_COLLECTABLE.ID));
  }

  private static <V> Keyset<V> keysetOf(Field<V> sortValue) {
    return Keyset.<V>builder().sortValue(sortValue).itemId(BASE_COLLECTABLE.ID).build();
  }
}
