package com.streamarr.server.repositories.media;

import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.media.Series;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.jooq.generated.enums.ExternalSourceType;
import com.streamarr.server.repositories.JooqQueryHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;

@RequiredArgsConstructor
public class SeriesRepositoryCustomImpl implements SeriesRepositoryCustom {

  private final DSLContext context;
  @PersistenceContext private final EntityManager entityManager;

  public Optional<Series> findByTmdbId(String tmdbId) {
    var query =
        context
            .select(Tables.SERIES.asterisk(), Tables.BASE_COLLECTABLE.asterisk())
            .from(Tables.SERIES)
            .innerJoin(Tables.BASE_COLLECTABLE)
            .on(Tables.SERIES.ID.eq(Tables.BASE_COLLECTABLE.ID))
            .join(Tables.EXTERNAL_IDENTIFIER)
            .on(Tables.EXTERNAL_IDENTIFIER.ENTITY_ID.eq(Tables.SERIES.ID))
            .where(
                Tables.EXTERNAL_IDENTIFIER
                    .EXTERNAL_SOURCE_TYPE
                    .eq(inline(ExternalSourceType.TMDB))
                    .and(Tables.EXTERNAL_IDENTIFIER.EXTERNAL_ID.eq(tmdbId)))
            .limit(1);

    List<Series> results = JooqQueryHelper.nativeQuery(entityManager, query, Series.class);

    if (results.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(results.get(0));
  }
}
