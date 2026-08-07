package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.UUID;

public interface PersonRepositoryCustom {

  boolean insertIfAbsent(String sourceId, String name);

  List<Person> findCastByMovieId(UUID movieId);

  List<Person> findDirectorsByMovieId(UUID movieId);

  List<Person> findCastBySeriesId(UUID seriesId);

  List<Person> findDirectorsBySeriesId(UUID seriesId);
}
