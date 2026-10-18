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
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dto.AlphabetIndexDto;
import com.streamarr.server.graphql.inputs.AddLibraryInput;
import com.streamarr.server.graphql.inputs.MediaFilterInput;
import com.streamarr.server.graphql.inputs.MediaFilterMapper;
import com.streamarr.server.graphql.inputs.MediaFilterRequest;
import com.streamarr.server.graphql.inputs.MediaSortInput;
import com.streamarr.server.graphql.inputs.RemoveLibraryInput;
import com.streamarr.server.graphql.inputs.StartLibraryRefreshInput;
import com.streamarr.server.graphql.inputs.StartLibraryScanInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.library.AddLibraryErrors;
import com.streamarr.server.graphql.mutation.library.AddLibraryPayload;
import com.streamarr.server.graphql.mutation.library.LibraryOperationErrors;
import com.streamarr.server.graphql.mutation.library.RemoveLibraryPayload;
import com.streamarr.server.graphql.mutation.library.StartLibraryRefreshPayload;
import com.streamarr.server.graphql.mutation.library.StartLibraryScanPayload;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.services.library.LibraryManagementService;
import com.streamarr.server.services.pagination.MediaPaginationOptionsResolver;
import com.streamarr.server.services.pagination.PaginationService;
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
  private final AuthorizationService authorizationService;
  private final LibraryAdministrationService libraryAdministrationService;
  private final LibraryManagementService libraryManagementService;
  private final MovieService movieService;
  private final SeriesService seriesService;
  private final PaginationService paginationService;
  private final CursorUtil cursorUtil;
  private final CursorValidator cursorValidator;
  private final RelayConnectionAdapter relayConnectionAdapter;

  @DgsMutation
  public AddLibraryPayload addLibrary(@InputArgument AddLibraryInput input) {
    var identity = authorizationService.currentIdentity();
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

    return MutationPayloads.payload(
        libraryAdministrationService.addLibrary(identity, library),
        AddLibraryErrors::toError,
        AddLibraryPayload::new);
  }

  @DgsMutation
  public RemoveLibraryPayload removeLibrary(@InputArgument RemoveLibraryInput input) {
    return MutationPayloads.withUuid(
        input.libraryId(),
        libraryId ->
            MutationPayloads.payload(
                libraryAdministrationService
                    .removeLibrary(authorizationService.currentIdentity(), libraryId)
                    .map(UUID::toString),
                LibraryOperationErrors::toRemoveLibraryError,
                RemoveLibraryPayload::new),
        () ->
            MutationPayloads.inputError(
                LibraryOperationErrors.invalidLibraryId(), RemoveLibraryPayload::new));
  }

  @DgsMutation
  public StartLibraryScanPayload startLibraryScan(@InputArgument StartLibraryScanInput input) {
    return MutationPayloads.withUuid(
        input.libraryId(),
        libraryId ->
            MutationPayloads.payload(
                libraryAdministrationService.startLibraryScan(
                    authorizationService.currentIdentity(), libraryId),
                LibraryOperationErrors::toStartLibraryScanError,
                StartLibraryScanPayload::new),
        () ->
            MutationPayloads.inputError(
                LibraryOperationErrors.invalidLibraryId(), StartLibraryScanPayload::new));
  }

  @DgsMutation
  public StartLibraryRefreshPayload startLibraryRefresh(
      @InputArgument StartLibraryRefreshInput input) {
    return MutationPayloads.withUuid(
        input.libraryId(),
        libraryId ->
            MutationPayloads.payload(
                libraryAdministrationService.startLibraryRefresh(
                    authorizationService.currentIdentity(), libraryId, input.imageRefreshMode()),
                LibraryOperationErrors::toStartLibraryRefreshError,
                StartLibraryRefreshPayload::new),
        () ->
            MutationPayloads.inputError(
                LibraryOperationErrors.invalidLibraryId(), StartLibraryRefreshPayload::new));
  }

  @DgsQuery
  public List<Library> libraries() {
    authorizationService.requireProfile();
    return libraryRepository.findAll();
  }

  @DgsQuery
  public Optional<Library> library(String id) {
    authorizationService.requireProfile();
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

    var effectiveFilter =
        MediaFilterMapper.from(
            MediaFilterRequest.builder()
                .libraryId(library.getId())
                .profileId(authorizationService.requireProfile())
                .sort(sort)
                .filter(filter)
                .build());
    var paginationOptions = paginationService.getPaginationOptions(first, after, last, before);
    var options =
        MediaPaginationOptionsResolver.resolve(
            paginationOptions,
            effectiveFilter,
            cursorUtil::decodeMediaCursor,
            cursorValidator::validateCursorAgainstFilter);

    var page =
        switch (library.getType()) {
          case MOVIE -> movieService.getMoviesWithFilter(options);
          case SERIES -> seriesService.getSeriesWithFilter(options);
          default -> throw new UnsupportedMediaTypeException(library.getType().name());
        };

    return relayConnectionAdapter.toConnection(page, options);
  }

  @DgsData(parentType = "Library")
  public List<AlphabetIndexDto> alphabetIndex(DataFetchingEnvironment dfe) {
    authorizationService.requireProfile();
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
