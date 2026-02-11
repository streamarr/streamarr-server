package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID>, PersonRepositoryCustom {

  Set<Person> findPersonsBySourceIdIn(List<String> sourceIds);

  Optional<Person> findPersonBySourceId(String sourceId);

  @Query("SELECT p FROM Movie m JOIN m.cast p WHERE m.id = :movieId")
  List<Person> findCastByMovieId(@Param("movieId") UUID movieId);

  @Query("SELECT p FROM Movie m JOIN m.directors p WHERE m.id = :movieId")
  List<Person> findDirectorsByMovieId(@Param("movieId") UUID movieId);

  @Query("SELECT p FROM Series s JOIN s.cast p WHERE s.id = :seriesId")
  List<Person> findCastBySeriesId(@Param("seriesId") UUID seriesId);

  @Query("SELECT p FROM Series s JOIN s.directors p WHERE s.id = :seriesId")
  List<Person> findDirectorsBySeriesId(@Param("seriesId") UUID seriesId);
}
