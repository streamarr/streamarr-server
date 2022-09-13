package com.streamarr.server.repositories;

import com.streamarr.server.db.Tables;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.media.Movie;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.RecordMapper;
import org.jooq.RecordMapperProvider;
import org.jooq.RecordType;
import org.jooq.impl.DefaultRecordMapper;

import java.util.List;

@RequiredArgsConstructor
public class BaseCollectableRepositoryCustomImpl implements BaseCollectableRepositoryCustom {

    private final DSLContext context;

    public static class TestMapper<R extends Record, E> implements RecordMapper<R, E> {

        @Nullable
        @Override
        public BaseCollectable map(Record record) {

            return Movie.builder()
                .id(record.getValue(Tables.BASE_COLLECTABLE.ID))
                .artwork(record.getValue(Tables.MOVIE.ARTWORK))
                .createdBy(record.getValue(Tables.BASE_COLLECTABLE.CREATED_BY))
                .build();
        }
    }

    public List<BaseCollectable> getBaseCollectableEntities() {
        return context.configuration()
            .set(
                new RecordMapperProvider() {
                    @Override
                    public @NotNull <R extends Record, E> RecordMapper<R, E> provide(RecordType<R> recordType, Class<? extends E> type) {

                        if (type == BaseCollectable.class) {
                            return new TestMapper<>();
                        }

                        // Fall back to jOOQ's DefaultRecordMapper
                        return new DefaultRecordMapper<>(recordType, type);
                    }
                }
            ).dsl()
            .select()
            .from(Tables.BASE_COLLECTABLE)
            .leftOuterJoin(Tables.MOVIE)
            .on(Tables.BASE_COLLECTABLE.ID.eq(Tables.MOVIE.ID))
            .leftOuterJoin(Tables.SERIES)
            .on(Tables.BASE_COLLECTABLE.ID.eq(Tables.SERIES.ID))
            .orderBy(Tables.BASE_COLLECTABLE.TITLE)
            .fetch(r -> {
                if (r.into(Tables.MOVIE).get(Tables.MOVIE.ID) != null) {
                    return r.into(com.streamarr.server.domain.media.Movie.class);
                }

                if (r.into(Tables.SERIES).get(Tables.SERIES.ID) != null) {
                    return r.into(com.streamarr.server.domain.media.Series.class);
                }

                return null;
            });
    }
}
