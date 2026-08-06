package com.streamarr.server.repositories;

import static com.streamarr.server.jooq.generated.tables.MovieDirector.MOVIE_DIRECTOR;
import static com.streamarr.server.jooq.generated.tables.MoviePerson.MOVIE_PERSON;
import static com.streamarr.server.jooq.generated.tables.Person.PERSON;
import static com.streamarr.server.jooq.generated.tables.SeriesDirector.SERIES_DIRECTOR;
import static com.streamarr.server.jooq.generated.tables.SeriesPerson.SERIES_PERSON;

import com.streamarr.server.domain.metadata.Person;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.data.domain.AuditorAware;

@RequiredArgsConstructor
public class PersonRepositoryCustomImpl implements PersonRepositoryCustom {

  private final DSLContext dsl;
  private final AuditorAware<UUID> auditorAware;
  @PersistenceContext private final EntityManager entityManager;

  @Override
  public boolean insertIfAbsent(String sourceId, String name) {
    var auditUser = auditorAware.getCurrentAuditor().orElse(null);
    var rowsAffected =
        dsl.insertInto(PERSON)
            .set(PERSON.SOURCE_ID, sourceId)
            .set(PERSON.NAME, name)
            .set(PERSON.CREATED_BY, auditUser)
            .set(PERSON.LAST_MODIFIED_BY, auditUser)
            .onConflict(PERSON.SOURCE_ID)
            .doNothing()
            .execute();
    return rowsAffected > 0;
  }

  @Override
  public List<Person> findCastByMovieId(UUID movieId) {
    var query =
        dsl.select(PERSON.asterisk())
            .from(PERSON)
            .innerJoin(MOVIE_PERSON)
            .on(MOVIE_PERSON.PERSON_ID.eq(PERSON.ID))
            .where(MOVIE_PERSON.MOVIE_ID.eq(movieId))
            .orderBy(MOVIE_PERSON.ORDINAL.asc().nullsLast(), MOVIE_PERSON.PERSON_ID.asc());

    return JooqQueryHelper.nativeQuery(entityManager, query, Person.class);
  }

  @Override
  public List<Person> findDirectorsByMovieId(UUID movieId) {
    var query =
        dsl.select(PERSON.asterisk())
            .from(PERSON)
            .innerJoin(MOVIE_DIRECTOR)
            .on(MOVIE_DIRECTOR.PERSON_ID.eq(PERSON.ID))
            .where(MOVIE_DIRECTOR.MOVIE_ID.eq(movieId))
            .orderBy(MOVIE_DIRECTOR.ORDINAL.asc().nullsLast(), MOVIE_DIRECTOR.PERSON_ID.asc());

    return JooqQueryHelper.nativeQuery(entityManager, query, Person.class);
  }

  @Override
  public List<Person> findCastBySeriesId(UUID seriesId) {
    var query =
        dsl.select(PERSON.asterisk())
            .from(PERSON)
            .innerJoin(SERIES_PERSON)
            .on(SERIES_PERSON.PERSON_ID.eq(PERSON.ID))
            .where(SERIES_PERSON.SERIES_ID.eq(seriesId))
            .orderBy(SERIES_PERSON.ORDINAL.asc().nullsLast(), SERIES_PERSON.PERSON_ID.asc());

    return JooqQueryHelper.nativeQuery(entityManager, query, Person.class);
  }

  @Override
  public List<Person> findDirectorsBySeriesId(UUID seriesId) {
    var query =
        dsl.select(PERSON.asterisk())
            .from(PERSON)
            .innerJoin(SERIES_DIRECTOR)
            .on(SERIES_DIRECTOR.PERSON_ID.eq(PERSON.ID))
            .where(SERIES_DIRECTOR.SERIES_ID.eq(seriesId))
            .orderBy(SERIES_DIRECTOR.ORDINAL.asc().nullsLast(), SERIES_DIRECTOR.PERSON_ID.asc());

    return JooqQueryHelper.nativeQuery(entityManager, query, Person.class);
  }
}
