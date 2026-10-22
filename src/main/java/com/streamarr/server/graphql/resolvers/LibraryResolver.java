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
import com.streamarr.server.graphql.cursor.LibraryItemCursors;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
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
import com.streamarr.server.graphql.inputs.StopLibraryRefreshInput;
import com.streamarr.server.graphql.inputs.StopLibraryScanInput;
import com.streamarr.server.graphql.mutation.MutationPayloads;
import com.streamarr.server.graphql.mutation.library.AddLibraryErrors;
import com.streamarr.server.graphql.mutation.library.AddLibraryPayload;
import com.streamarr.server.graphql.mutation.library.LibraryOperationErrors;
import com.streamarr.server.graphql.mutation.library.RemoveLibraryPayload;
import com.streamarr.server.graphql.mutation.library.StartLibraryRefreshPayload;
import com.streamarr.server.graphql.mutation.library.StartLibraryScanPayload;
import com.streamarr.server.graphql.mutation.library.StopLibraryRefreshPayload;
import com.streamarr.server.graphql.mutation.library.StopLibraryScanPayload;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.services.library.LibraryCountsService.Counts;
import com.streamarr.server.services.library.LibraryQueryService;
import com.streamarr.server.services.pagination.PaginationService;
import graphql.relay.Connection;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;

@DgsComponent
@RequiredArgsConstructor
public class LibraryResolver {

  private final LibraryQueryService libraryQueries;
  private final AuthorizationService authorizationService;
  private final LibraryAdministrationService libraryAdministrationService;
  private final PaginationService paginationService;
  private final PositionCursorCodec positionCursors;
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

  @DgsMutation
  public StopLibraryScanPayload stopLibraryScan(@InputArgument StopLibraryScanInput input) {
    return MutationPayloads.withUuid(
        input.libraryId(),
        libraryId ->
            MutationPayloads.payload(
                libraryAdministrationService.stopLibraryScan(
                    authorizationService.currentIdentity(), libraryId),
                LibraryOperationErrors::toStopLibraryScanError,
                StopLibraryScanPayload::new),
        () ->
            MutationPayloads.inputError(
                LibraryOperationErrors.invalidLibraryId(), StopLibraryScanPayload::new));
  }

  @DgsMutation
  public StopLibraryRefreshPayload stopLibraryRefresh(
      @InputArgument StopLibraryRefreshInput input) {
    return MutationPayloads.withUuid(
        input.libraryId(),
        libraryId ->
            MutationPayloads.payload(
                libraryAdministrationService.stopLibraryRefresh(
                    authorizationService.currentIdentity(), libraryId),
                LibraryOperationErrors::toStopLibraryRefreshError,
                StopLibraryRefreshPayload::new),
        () ->
            MutationPayloads.inputError(
                LibraryOperationErrors.invalidLibraryId(), StopLibraryRefreshPayload::new));
  }

  @DgsQuery
  public List<Library> libraries() {
    return libraryQueries.findAll(authorizationService.currentIdentity());
  }

  @DgsQuery
  public Optional<Library> library(String id) {
    authorizationService.requireProfile();
    return libraryQueries.findById(authorizationService.currentIdentity(), parseUuid(id));
  }

  @DgsData(parentType = "Library")
  public Connection<BaseCollectable<?>> items(
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
    var list = LibraryItemCursors.listOf(effectiveFilter);
    var request =
        positionCursors.pageRequest(
            list, paginationService.getPaginationOptions(first, after, last, before));

    var page =
        libraryQueries.getItems(
            authorizationService.currentIdentity(), library.getType(), effectiveFilter, request);

    return relayConnectionAdapter.toOrderedConnection(
        page, list, LibraryItemCursors.orderOf(effectiveFilter));
  }

  @DgsData(parentType = "Library")
  public CompletableFuture<List<AlphabetIndexDto>> alphabetIndex(DataFetchingEnvironment dfe) {
    authorizationService.requireProfile();
    Library library = dfe.getSource();
    DataLoader<UUID, Counts> loader = dfe.getDataLoader("libraryItemCount");
    return loader
        .load(library.getId())
        .thenApply(
            counts ->
                counts.alphabetIndex().stream()
                    .map(m -> new AlphabetIndexDto(m.getLetter(), m.getItemCount()))
                    .toList());
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
