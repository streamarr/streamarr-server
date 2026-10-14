package com.streamarr.server.domain.media;

import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.Set;

/** A media title whose credits list its cast, directors, genres and studios. */
public interface CreditedMediaTitle {

  List<Person> getCast();

  CreditedMediaTitle setCast(List<Person> cast);

  List<Person> getDirectors();

  CreditedMediaTitle setDirectors(List<Person> directors);

  Set<Genre> getGenres();

  CreditedMediaTitle setGenres(Set<Genre> genres);

  Set<Company> getStudios();

  CreditedMediaTitle setStudios(Set<Company> studios);
}
