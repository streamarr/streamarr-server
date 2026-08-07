package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.Company.COMPANY;
import static com.streamarr.server.jooq.generated.tables.MovieCompany.MOVIE_COMPANY;
import static com.streamarr.server.jooq.generated.tables.SeriesCompany.SERIES_COMPANY;

import com.streamarr.server.domain.metadata.Company;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class CompanyRepositoryCustomImpl implements CompanyRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  private final EntityManager entityManager;

  @Override
  public boolean insertIfAbsent(String sourceId, String name) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var rowsAffected =
        dsl.insertInto(COMPANY)
            .set(COMPANY.SOURCE_ID, sourceId)
            .set(COMPANY.NAME, name)
            .set(COMPANY.CREATED_BY, auditUser)
            .set(COMPANY.LAST_MODIFIED_BY, auditUser)
            .onConflict(COMPANY.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }

  @Override
  public List<Company> findByMovieId(UUID movieId) {
    var query =
        dsl.select(COMPANY.asterisk())
            .from(COMPANY)
            .innerJoin(MOVIE_COMPANY)
            .on(MOVIE_COMPANY.COMPANY_ID.eq(COMPANY.ID))
            .where(MOVIE_COMPANY.MOVIE_ID.eq(movieId));

    return JooqQueryHelper.nativeQuery(entityManager, query, Company.class);
  }

  @Override
  public List<Company> findBySeriesId(UUID seriesId) {
    var query =
        dsl.select(COMPANY.asterisk())
            .from(COMPANY)
            .innerJoin(SERIES_COMPANY)
            .on(SERIES_COMPANY.COMPANY_ID.eq(COMPANY.ID))
            .where(SERIES_COMPANY.SERIES_ID.eq(seriesId));

    return JooqQueryHelper.nativeQuery(entityManager, query, Company.class);
  }
}
