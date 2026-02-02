package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.repositories.media.MovieRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class MovieResolver {

  private final MovieRepository movieRepository;

  @DgsQuery
  public Optional<Movie> movie(String id) {
    return movieRepository.findById(parseUuid(id));
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException ex) {
      throw new InvalidIdException(id);
    }
  }
}
