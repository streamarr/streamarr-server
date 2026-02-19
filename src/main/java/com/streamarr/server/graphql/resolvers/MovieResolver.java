package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.services.MovieService;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MovieResolver {

  private final MovieService movieService;

  @DgsQuery
  public Optional<Movie> movie(String id) {
    return movieService.findById(parseUuid(id));
  }

  @DgsData(parentType = "Movie", field = "files")
  public List<MediaFile> files(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return movieService.findMediaFiles(movie.getId());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
