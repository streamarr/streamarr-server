package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MovieResolver {

  private final MovieRepository movieRepository;
  private final MediaFileRepository mediaFileRepository;

  @DgsQuery
  public Optional<Movie> movie(String id) {
    return movieRepository.findById(parseUuid(id));
  }

  @DgsData(parentType = "Movie", field = "files")
  public List<MediaFile> files(DataFetchingEnvironment dfe) {
    Movie movie = dfe.getSource();
    return mediaFileRepository.findByMediaId(movie.getId());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException ex) {
      throw new InvalidIdException(id);
    }
  }
}
