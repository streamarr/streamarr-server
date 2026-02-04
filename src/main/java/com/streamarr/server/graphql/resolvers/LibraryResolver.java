package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.library.LibraryManagementService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@DgsComponent
@RequiredArgsConstructor
public class LibraryResolver {

  private final LibraryRepository libraryRepository;
  private final LibraryManagementService libraryManagementService;
  private final MovieService movieService;

  @DgsMutation
  public boolean scanLibrary(String id) {
    libraryManagementService.scanLibrary(parseUuid(id));
    return true;
  }

  @DgsQuery
  public List<Library> libraries() {
    return libraryRepository.findAll();
  }

  @DgsQuery
  public Optional<Library> library(String id) {
    return libraryRepository.findById(parseUuid(id));
  }

  @DgsData(parentType = "Library")
  public Connection<? extends BaseCollectable<?>> items(
      @InputArgument MediaFilter filter, DataFetchingEnvironment dfe) {
    Library library = dfe.getSource();
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");

    var effectiveFilter =
        (filter != null ? filter.toBuilder() : MediaFilter.builder())
            .libraryId(library.getId())
            .build();

    return switch (library.getType()) {
      case MOVIE -> movieService.getMoviesWithFilter(first, after, last, before, effectiveFilter);
      default -> throw new UnsupportedMediaTypeException(library.getType().name());
    };
  }

  @DgsTypeResolver(name = "Media")
  public String resolveMedia(Object media) {
    if (media instanceof Movie) {
      return "Movie";
    }

    throw new UnsupportedMediaTypeException(media.getClass().getName());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException ex) {
      throw new InvalidIdException(id);
    }
  }
}
