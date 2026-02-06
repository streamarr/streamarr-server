package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class SeriesFieldResolver {

  private final CompanyRepository companyRepository;
  private final PersonRepository personRepository;
  private final GenreRepository genreRepository;
  private final SeasonRepository seasonRepository;

  @DgsData(parentType = "Series", field = "studios")
  public List<Company> studios(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return companyRepository.findBySeriesId(series.getId());
  }

  @DgsData(parentType = "Series", field = "cast")
  public List<Person> cast(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return personRepository.findCastBySeriesId(series.getId());
  }

  @DgsData(parentType = "Series", field = "directors")
  public List<Person> directors(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return personRepository.findDirectorsBySeriesId(series.getId());
  }

  @DgsData(parentType = "Series", field = "genres")
  public List<Genre> genres(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return genreRepository.findBySeriesId(series.getId());
  }

  @DgsData(parentType = "Series", field = "seasons")
  public List<Season> seasons(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seasonRepository.findBySeriesId(series.getId());
  }
}
