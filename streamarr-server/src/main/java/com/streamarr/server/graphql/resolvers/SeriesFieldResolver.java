package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.SeriesService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class SeriesFieldResolver {

  private final SeriesService seriesService;

  @DgsData(parentType = "Series", field = "studios")
  public List<Company> studios(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seriesService.findStudios(series.getId());
  }

  @DgsData(parentType = "Series", field = "cast")
  public List<Person> cast(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seriesService.findCast(series.getId());
  }

  @DgsData(parentType = "Series", field = "directors")
  public List<Person> directors(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seriesService.findDirectors(series.getId());
  }

  @DgsData(parentType = "Series", field = "genres")
  public List<Genre> genres(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seriesService.findGenres(series.getId());
  }

  @DgsData(parentType = "Series", field = "seasons")
  public List<Season> seasons(DataFetchingEnvironment dfe) {
    Series series = dfe.getSource();
    return seriesService.findSeasons(series.getId());
  }
}
