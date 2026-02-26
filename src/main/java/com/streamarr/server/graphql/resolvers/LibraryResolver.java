package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.netflix.graphql.dgs.InputArgument;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.InvalidIdException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.dto.AlphabetIndexDto;
import com.streamarr.server.graphql.inputs.AddLibraryInput;
import com.streamarr.server.graphql.inputs.MediaFilterInput;
import com.streamarr.server.graphql.inputs.MediaSortInput;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
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
  private final SeriesService seriesService;

  @DgsMutation
  public Library addLibrary(@InputArgument AddLibraryInput input) {
    var library =
        Library.builder()
            .name(input.name())
            .filepathUri(input.filepath())
            .type(input.type())
            .backend(input.backend())
            .externalAgentStrategy(
                input.externalAgentStrategy() != null
                    ? input.externalAgentStrategy()
                    : ExternalAgentStrategy.TMDB)
            .build();

    return libraryManagementService.addLibrary(library);
  }

  @DgsMutation
  public boolean removeLibrary(String id) {
    libraryManagementService.removeLibrary(parseUuid(id));
    return true;
  }

  @DgsMutation
  public boolean scanLibrary(String id) {
    libraryManagementService.triggerAsyncScan(parseUuid(id));
    return true;
  }

  @DgsMutation
  public boolean refreshLibrary(String id) {
    libraryManagementService.triggerAsyncRefresh(parseUuid(id));
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
      @InputArgument MediaSortInput sort,
      @InputArgument MediaFilterInput filter,
      DataFetchingEnvironment dfe) {
    Library library = dfe.getSource();
    int first = dfe.getArgumentOrDefault("first", 0);
    String after = dfe.getArgument("after");
    int last = dfe.getArgumentOrDefault("last", 0);
    String before = dfe.getArgument("before");

    var builder = MediaFilter.builder().libraryId(library.getId());

    if (sort != null) {
      if (sort.by() != null) {
        builder.sortBy(sort.by());
      }
      if (sort.direction() != null) {
        builder.sortDirection(sort.direction());
      }
    }

    if (filter != null) {
      builder.startLetter(filter.startLetter());
    }

    var effectiveFilter = builder.build();

    return switch (library.getType()) {
      case MOVIE -> movieService.getMoviesWithFilter(first, after, last, before, effectiveFilter);
      case SERIES -> seriesService.getSeriesWithFilter(first, after, last, before, effectiveFilter);
      default -> throw new UnsupportedMediaTypeException(library.getType().name());
    };
  }

  @DgsData(parentType = "Library")
  public List<AlphabetIndexDto> alphabetIndex(DataFetchingEnvironment dfe) {
    Library library = dfe.getSource();
    return libraryManagementService.getAlphabetIndex(library.getId()).stream()
        .map(m -> new AlphabetIndexDto(m.getLetter(), m.getItemCount()))
        .toList();
  }

  @DgsTypeResolver(name = "Media")
  public String resolveMedia(Object media) {
    if (media instanceof Movie) {
      return "Movie";
    }

    if (media instanceof Series) {
      return "Series";
    }

    throw new UnsupportedMediaTypeException(media.getClass().getSimpleName());
  }

  private UUID parseUuid(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException _) {
      throw new InvalidIdException(id);
    }
  }
}
