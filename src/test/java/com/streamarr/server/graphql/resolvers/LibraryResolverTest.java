package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.BaseCollectable;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.exceptions.LibraryOperationNotStartedException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fakes.FakeLibraryMetadataRepository;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.graphql.dataloaders.LibraryItemCountDataLoader;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.library.AddLibraryRejection;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.services.library.LibraryCountsService;
import com.streamarr.server.services.library.LibraryManagementService;
import com.streamarr.server.services.library.LibraryOperationKind;
import com.streamarr.server.services.library.LibraryOperationRejection;
import com.streamarr.server.services.library.LibraryOperationService;
import com.streamarr.server.services.library.LibraryOperationStopRejection;
import com.streamarr.server.services.library.LibraryQueryService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import com.streamarr.server.support.security.WithAccountContext;
import com.streamarr.server.support.security.WithProfileContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      LibraryResolver.class,
      LibraryQueryService.class,
      LibraryItemCountDataLoader.class,
      LibraryAdministrationService.class,
      PaginationService.class,
      RelayConnectionAdapter.class,
      PositionCursorCodec.class,
      JacksonAutoConfiguration.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Library Resolver Tests")
class LibraryResolverTest {

  private static final FakeLibraryManagementService FAKE_LIBRARY_MANAGEMENT_SERVICE =
      new FakeLibraryManagementService();

  private static final FakeLibraryOperationService FAKE_LIBRARY_OPERATION_SERVICE =
      new FakeLibraryOperationService();

  private static final FakeLibraryMetadataRepository FAKE_COUNTS =
      new FakeLibraryMetadataRepository();

  @TestBean private LibraryCountsService libraryCountsService;

  static LibraryCountsService libraryCountsService() {
    return new LibraryCountsService(FAKE_COUNTS);
  }

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @Autowired private LibraryResolver libraryResolver;

  @Autowired private FakeAuthorizationDecider authorizationDecider;

  @Autowired private AuthorizationService authorizationService;

  @MockitoBean private UserAccountRepository userAccountRepository;

  @MockitoBean private LibraryRepository libraryRepository;

  @TestBean private LibraryManagementService libraryManagementService;

  @MockitoBean private MovieService movieService;

  @MockitoBean private SeriesService seriesService;

  @TestBean private LibraryOperationService libraryOperationService;

  static LibraryManagementService libraryManagementService() {
    return FAKE_LIBRARY_MANAGEMENT_SERVICE;
  }

  static LibraryOperationService libraryOperationService() {
    return FAKE_LIBRARY_OPERATION_SERVICE;
  }

  @BeforeEach
  void resetLibraryServices() {
    FAKE_LIBRARY_MANAGEMENT_SERVICE.reset();
    FAKE_LIBRARY_OPERATION_SERVICE.reset();
  }

  @Nested
  @DisplayName("Library Queries")
  class LibraryQueries {

    @Test
    @DisplayName("Should return library when valid ID provided")
    void shouldReturnLibraryWhenValidIdProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      String name =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ library(id: \"%s\") { name filepathUri } }", libraryId),
              "data.library.name");

      assertThat(name).isEqualTo("Movies");
    }

    @Test
    @DisplayName("Should return all libraries when queried")
    void shouldReturnAllLibrariesWhenQueried() {
      var moviesLibrary =
          Library.builder()
              .name("Movies")
              .filepathUri("file:///mpool/media/movies")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.MOVIE)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();

      var showsLibrary =
          Library.builder()
              .name("TV Shows")
              .filepathUri("file:///mpool/media/shows")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.SERIES)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();

      when(libraryRepository.findAll()).thenReturn(List.of(moviesLibrary, showsLibrary));

      List<String> names =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ libraries { name } }", "data.libraries[*].name");

      assertThat(names).containsExactly("Movies", "TV Shows");
    }

    @Test
    @DisplayName("Should return background-created library when creator absent")
    @SuppressWarnings("unchecked")
    void shouldReturnBackgroundCreatedLibraryWhenCreatorAbsent() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      var result =
          dgsQueryExecutor.execute(
              String.format("{ library(id: \"%s\") { name createdBy } }", libraryId));

      assertThat(result.getErrors()).isEmpty();
      var data = result.<Map<String, Object>>getData();
      var libraryData = (Map<String, Object>) data.get("library");
      assertThat(libraryData).containsEntry("name", "Movies").containsEntry("createdBy", null);
    }
  }

  @Nested
  @DisplayName("Library Mutations")
  @WithProfileContext
  class LibraryMutations {

    @AfterEach
    void allowAgain() {
      authorizationDecider.allowAll();
    }

    @Test
    @DisplayName(
        "Should return the library SCANNING with no userErrors when startLibraryScan starts an idle library")
    void shouldReturnLibraryScanningWithNoUserErrorsWhenStartLibraryScanStartsIdleLibrary() {
      var libraryId = UUID.randomUUID();

      var payload = payloadOf(LibraryOperationMutation.START_LIBRARY_SCAN, libraryId.toString());

      assertThat(payload)
          .containsEntry("library", Map.of("id", libraryId.toString(), "status", "SCANNING"))
          .containsEntry("userErrors", List.of());
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.startedLibraryIds()).containsExactly(libraryId);
      assertThat(authorizationDecider.recordedIntents())
          .contains(new Intent.ScanLibrary(libraryId));
    }

    @Test
    @DisplayName(
        "Should return the library REFRESHING with no userErrors when startLibraryRefresh starts an idle library")
    void shouldReturnLibraryRefreshingWithNoUserErrorsWhenStartLibraryRefreshStartsIdleLibrary() {
      var libraryId = UUID.randomUUID();

      var payload = payloadOf(LibraryOperationMutation.START_LIBRARY_REFRESH, libraryId.toString());

      assertThat(payload)
          .containsEntry("library", Map.of("id", libraryId.toString(), "status", "REFRESHING"))
          .containsEntry("userErrors", List.of());
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.refreshRequest())
          .isEqualTo(new RefreshRequest(libraryId, ImageRefreshMode.PRESERVE));
      assertThat(authorizationDecider.recordedIntents())
          .contains(new Intent.RefreshLibrary(libraryId));
    }

    @Test
    @DisplayName("Should pass the image refresh mode when the startLibraryRefresh input names one")
    void shouldPassImageRefreshModeWhenStartLibraryRefreshInputNamesOne() {
      var libraryId = UUID.randomUUID();

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              """
              mutation {
                startLibraryRefresh(
                  input: { libraryId: "%s", imageRefreshMode: REFRESH_IF_CHANGED }
                ) {
                  library { status }
                }
              }
              """
                  .formatted(libraryId),
              "data.startLibraryRefresh.library.status");

      assertThat(status).isEqualTo("REFRESHING");
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.refreshRequest())
          .isEqualTo(new RefreshRequest(libraryId, ImageRefreshMode.REFRESH_IF_CHANGED));
    }

    @Test
    @DisplayName(
        "Should reject the request when the startLibraryRefresh input sets imageRefreshMode to null")
    void shouldRejectRequestWhenStartLibraryRefreshInputSetsImageRefreshModeToNull() {
      var result =
          dgsQueryExecutor.execute(
              """
              mutation {
                startLibraryRefresh(input: { libraryId: "%s", imageRefreshMode: null }) {
                  library { status }
                }
              }
              """
                  .formatted(UUID.randomUUID()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(error -> assertThat(error.getMessage()).contains("imageRefreshMode"));
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.startedLibraryIds()).isEmpty();
    }

    @Test
    @DisplayName(
        "Should return the removed library's id with no userErrors when removeLibrary removes an idle library")
    void shouldReturnRemovedLibraryIdWithNoUserErrorsWhenRemoveLibraryRemovesIdleLibrary() {
      var libraryId = UUID.randomUUID();

      var payload = payloadOf(LibraryOperationMutation.REMOVE_LIBRARY, libraryId.toString());

      assertThat(payload)
          .containsEntry("removedLibraryId", libraryId.toString())
          .containsEntry("userErrors", List.of());
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.removedLibraryIds()).containsExactly(libraryId);
      assertThat(authorizationDecider.recordedIntents())
          .contains(new Intent.RemoveLibrary(libraryId));
    }

    @ParameterizedTest(name = "Should return the library STOPPING with no userErrors when {0} runs")
    @EnumSource(
        value = LibraryOperationMutation.class,
        names = {"STOP_LIBRARY_SCAN", "STOP_LIBRARY_REFRESH"})
    @DisplayName(
        "Should return the library STOPPING with no userErrors through the stop's own intent when a"
            + " stop is stored")
    void shouldReturnTheLibraryStoppingWithNoUserErrorsWhenAStopIsStored(
        LibraryOperationMutation mutation) {
      var libraryId = UUID.randomUUID();
      var kind = LibraryOperationKind.SCAN;
      Intent intent = new Intent.StopLibraryScan(libraryId);
      if (mutation == LibraryOperationMutation.STOP_LIBRARY_REFRESH) {
        kind = LibraryOperationKind.REFRESH;
        intent = new Intent.StopLibraryRefresh(libraryId);
      }

      var payload = payloadOf(mutation, libraryId.toString());

      assertThat(payload)
          .containsEntry("library", Map.of("id", libraryId.toString(), "status", "STOPPING"))
          .containsEntry("userErrors", List.of());
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.stopRequests())
          .containsExactly(new StopRequest(libraryId, kind));
      assertThat(authorizationDecider.recordedIntents()).contains(intent);
    }

    @ParameterizedTest(name = "Should return {3} for {0} when the library shows {1}")
    @MethodSource(
        "com.streamarr.server.graphql.resolvers.LibraryResolverTest#notInProgressRefusals")
    @DisplayName(
        "Should return the not-in-progress error naming the running kind and no result when no"
            + " operation of the requested kind runs")
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    void shouldReturnTheNotInProgressErrorNamingTheRunningKindWhenNoOperationOfTheKindRuns(
        LibraryOperationMutation mutation,
        LibraryStatus status,
        Optional<LibraryOperationKind> runningKind,
        String typename,
        String message) {
      FAKE_LIBRARY_OPERATION_SERVICE.rejectStopsWith(
          new LibraryOperationStopRejection.NotInProgress(status, runningKind));

      var payload = payloadOf(mutation, UUID.randomUUID().toString());

      assertThat(payload).containsEntry("library", null);
      assertThat(userErrorsOf(payload))
          .singleElement()
          .isEqualTo(Map.of("__typename", typename, "message", message, "status", status.name()));
    }

    @ParameterizedTest(
        name = "Should return LibraryNotFoundError and no result when {0} names a missing library")
    @EnumSource(LibraryOperationMutation.class)
    @DisplayName(
        "Should return LibraryNotFoundError at libraryId and no result when the library is missing")
    void shouldReturnLibraryNotFoundErrorAndNoResultWhenLibraryIsMissing(
        LibraryOperationMutation mutation) {
      rejectEveryRequestWith(new LibraryOperationRejection.LibraryNotFound());
      FAKE_LIBRARY_OPERATION_SERVICE.rejectStopsWith(
          new LibraryOperationStopRejection.LibraryNotFound());

      var payload = payloadOf(mutation, UUID.randomUUID().toString());

      assertThat(payload).containsEntry(mutation.resultField(), null);
      assertThat(userErrorsOf(payload))
          .singleElement()
          .isEqualTo(
              Map.of(
                  "__typename", "LibraryNotFoundError",
                  "message", "That library does not exist.",
                  "inputPath", List.of("libraryId")));
    }

    @ParameterizedTest(
        name = "Should return LibraryBusyError with {2} and no result when {0} meets a running {1}")
    @MethodSource("com.streamarr.server.graphql.resolvers.LibraryResolverTest#libraryBusyRefusals")
    @DisplayName(
        "Should return LibraryBusyError naming the running kind and no result when the library is busy")
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    void shouldReturnLibraryBusyErrorNamingRunningKindAndNoResultWhenLibraryIsBusy(
        LibraryOperationMutation mutation,
        LibraryOperationKind runningKind,
        LibraryStatus status,
        String message) {
      rejectEveryRequestWith(new LibraryOperationRejection.LibraryBusy(status, runningKind));

      var payload = payloadOf(mutation, UUID.randomUUID().toString());

      assertThat(payload).containsEntry(mutation.resultField(), null);
      assertThat(userErrorsOf(payload))
          .singleElement()
          .isEqualTo(
              Map.of(
                  "__typename", "LibraryBusyError", "message", message, "status", status.name()));
    }

    @ParameterizedTest(
        name = "Should return InvalidIdError and no result when {0} receives a malformed id")
    @EnumSource(LibraryOperationMutation.class)
    @DisplayName(
        "Should return InvalidIdError at libraryId and start nothing when the libraryId is malformed")
    void shouldReturnInvalidIdErrorAndStartNothingWhenLibraryIdIsMalformed(
        LibraryOperationMutation mutation) {
      var payload = payloadOf(mutation, "not-a-uuid");

      assertThat(payload).containsEntry(mutation.resultField(), null);
      assertThat(userErrorsOf(payload))
          .singleElement()
          .isEqualTo(
              Map.of(
                  "__typename", "InvalidIdError",
                  "message", "Enter a valid ID.",
                  "inputPath", List.of("libraryId")));
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.startedLibraryIds()).isEmpty();
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.stopRequests()).isEmpty();
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.removedLibraryIds()).isEmpty();
    }

    @ParameterizedTest(name = "Should return FORBIDDEN and a null payload when {0} is denied")
    @EnumSource(LibraryOperationMutation.class)
    @DisplayName("Should return FORBIDDEN and a null payload when authorization denies")
    void shouldReturnForbiddenAndNullPayloadWhenAuthorizationDenies(
        LibraryOperationMutation mutation) {
      authorizationDecider.denyAll();

      var result = dgsQueryExecutor.execute(mutation.document(UUID.randomUUID().toString()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(error -> assertThat(error.getExtensions()).containsEntry("code", "FORBIDDEN"));
      assertThat(result.<Map<String, Object>>getData()).containsEntry(mutation.fieldName(), null);
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.startedLibraryIds()).isEmpty();
      assertThat(FAKE_LIBRARY_OPERATION_SERVICE.stopRequests()).isEmpty();
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.removedLibraryIds()).isEmpty();
    }

    @ParameterizedTest(
        name = "Should return LIBRARY_OPERATION_NOT_STARTED and a null payload when {0} fails")
    @EnumSource(
        value = LibraryOperationMutation.class,
        names = {"START_LIBRARY_SCAN", "START_LIBRARY_REFRESH"})
    @DisplayName(
        "Should return LIBRARY_OPERATION_NOT_STARTED and a null payload when the start transaction fails")
    void shouldReturnLibraryOperationNotStartedAndNullPayloadWhenStartTransactionFails(
        LibraryOperationMutation mutation) {
      FAKE_LIBRARY_OPERATION_SERVICE.throwWhenStarted(
          new LibraryOperationNotStartedException(
              new CannotAcquireLockException("connection to db.internal:5432 lost")));

      var result = dgsQueryExecutor.execute(mutation.document(UUID.randomUUID().toString()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(
              error -> {
                assertThat(error.getMessage())
                    .isEqualTo("No library scan or refresh started. Try again shortly.");
                assertThat(error.getExtensions())
                    .containsEntry("errorType", "UNAVAILABLE")
                    .containsEntry("code", "LIBRARY_OPERATION_NOT_STARTED")
                    .containsOnlyKeys("errorType", "code", "requestId");
              });
      assertThat(result.<Map<String, Object>>getData()).containsEntry(mutation.fieldName(), null);
    }

    @Test
    @DisplayName("Should return AUTHORIZATION_UNAVAILABLE when no decision could be made")
    void shouldReturnAuthorizationUnavailableWhenNoDecisionCouldBeMade() {
      authorizationDecider.failWith(Decision.FailureCause.ENGINE_FAILURE);

      var result =
          dgsQueryExecutor.execute(
              LibraryOperationMutation.REMOVE_LIBRARY.document(UUID.randomUUID().toString()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(
              error -> {
                assertThat(error.getExtensions())
                    .containsEntry("code", "AUTHORIZATION_UNAVAILABLE");
                assertThat(error.getMessage())
                    .contains("Authorization is temporarily unavailable.");
              });
      assertThat(result.<Map<String, Object>>getData()).containsEntry("removeLibrary", null);
    }

    @Test
    @DisplayName("Should return the library and no user errors when addLibrary is accepted")
    void shouldReturnLibraryAndNoUserErrorsWhenAddLibraryIsAccepted() {
      var expectedIdentity = authorizationService.currentIdentity();
      var library =
          Library.builder()
              .name("Movies")
              .filepathUri("file:///mpool/media/movies")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.MOVIE)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(UUID.randomUUID());

      FAKE_LIBRARY_MANAGEMENT_SERVICE.returnLibraryWhenAdded(library);

      var result = dgsQueryExecutor.execute(ADD_LIBRARY_MUTATION);

      assertThat(result.getErrors()).isEmpty();
      Map<String, Object> data = result.getData();
      @SuppressWarnings("unchecked")
      var payload = (Map<String, Object>) data.get("addLibrary");
      assertThat(payload)
          .containsEntry(
              "library", Map.of("name", "Movies", "filepathUri", "file:///mpool/media/movies"))
          .containsEntry("userErrors", List.of());
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.addedIdentity()).isEqualTo(expectedIdentity);
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.addedLibrary())
          .satisfies(
              input -> {
                assertThat(input.getName()).isEqualTo("Movies");
                assertThat(input.getFilepathUri()).isEqualTo("/mpool/media/movies");
                assertThat(input.getType()).isEqualTo(MediaType.MOVIE);
                assertThat(input.getBackend()).isEqualTo(LibraryBackend.LOCAL);
                assertThat(input.getExternalAgentStrategy()).isEqualTo(ExternalAgentStrategy.TMDB);
              });
    }

    @Test
    @DisplayName("Should return a sanitized top-level error when addLibrary fails unexpectedly")
    void shouldReturnSanitizedTopLevelErrorWhenAddLibraryFailsUnexpectedly() {
      FAKE_LIBRARY_MANAGEMENT_SERVICE.throwWhenAdded(
          new IllegalStateException(
              "duplicate key library_filepath_uri_idx at /srv/private/movies"));

      var result = dgsQueryExecutor.execute(ADD_LIBRARY_MUTATION);

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(
              error -> {
                assertThat(error.getMessage()).isEqualTo("The request could not be completed.");
                assertThat(error.getExtensions())
                    .containsEntry("errorType", "INTERNAL")
                    .containsEntry("code", "INTERNAL")
                    .containsOnlyKeys("errorType", "code", "requestId");
                assertThat((String) error.getExtensions().get("requestId"))
                    .startsWith("req-")
                    .hasSize(12);
              });
      assertThat(result.<Map<String, Object>>getData()).containsEntry("addLibrary", null);
    }

    @ParameterizedTest(name = "Should return {1} at {2} when addLibrary is rejected with {0}")
    @MethodSource("com.streamarr.server.graphql.resolvers.LibraryResolverTest#addLibraryRejections")
    @DisplayName("Should return the typed user error and no library when addLibrary is rejected")
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    void shouldReturnTypedUserErrorAndNoLibraryWhenAddLibraryIsRejected(
        AddLibraryRejection rejection, String typename, String inputPath) {
      FAKE_LIBRARY_MANAGEMENT_SERVICE.returnOutcomeWhenAdded(Outcome.rejected(rejection));

      var result = dgsQueryExecutor.execute(ADD_LIBRARY_MUTATION);

      assertThat(result.getErrors()).isEmpty();
      Map<String, Object> data = result.getData();
      @SuppressWarnings("unchecked")
      var payload = (Map<String, Object>) data.get("addLibrary");
      assertThat(payload).containsEntry("library", null);
      @SuppressWarnings("unchecked")
      var userErrors = (List<Map<String, Object>>) payload.get("userErrors");
      assertThat(userErrors)
          .singleElement()
          .satisfies(
              error -> {
                assertThat(error).containsEntry("__typename", typename);
                assertThat(error).containsEntry("inputPath", List.of(inputPath));
                assertThat((String) error.get("message")).isNotBlank().endsWith(".");
              });
    }

    @Test
    @DisplayName("Should report every rejection when addLibrary is rejected for several reasons")
    void shouldReportEveryRejectionWhenAddLibraryIsRejectedForSeveralReasons() {
      FAKE_LIBRARY_MANAGEMENT_SERVICE.returnOutcomeWhenAdded(
          Outcome.rejected(
              List.of(
                  new AddLibraryRejection.NameRequired(), new AddLibraryRejection.PathRequired())));

      List<String> typenames =
          dgsQueryExecutor.executeAndExtractJsonPath(
              ADD_LIBRARY_MUTATION, "data.addLibrary.userErrors[*].__typename");

      assertThat(typenames).containsExactly("LibraryNameRequiredError", "LibraryPathRequiredError");
    }

    @Test
    @DisplayName("Should return an invalid ID error when the library query receives a malformed id")
    void shouldReturnInvalidIdErrorWhenLibraryQueryReceivesMalformedId() {
      var result = dgsQueryExecutor.execute("{ library(id: \"not-a-uuid\") { name } }");

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(error -> assertThat(error.getMessage()).contains("Invalid ID format"));
    }
  }

  private static final String ADD_LIBRARY_MUTATION =
      """
      mutation {
        addLibrary(input: {
          name: "Movies"
          filepath: "/mpool/media/movies"
          type: MOVIE
          backend: LOCAL
          externalAgentStrategy: TMDB
        }) {
          library { name filepathUri }
          userErrors {
            __typename
            ... on MutationError { message }
            ... on InputMutationError { inputPath }
          }
        }
      }
      """;

  static Stream<Arguments> addLibraryRejections() {
    return Stream.of(
        Arguments.of(new AddLibraryRejection.NameRequired(), "LibraryNameRequiredError", "name"),
        Arguments.of(
            new AddLibraryRejection.PathRequired(), "LibraryPathRequiredError", "filepath"),
        Arguments.of(
            new AddLibraryRejection.PathNotFound(), "LibraryPathNotFoundError", "filepath"),
        Arguments.of(
            new AddLibraryRejection.PathNotDirectory(), "LibraryPathNotDirectoryError", "filepath"),
        Arguments.of(
            new AddLibraryRejection.PathNotReadable(), "LibraryPathNotReadableError", "filepath"),
        Arguments.of(
            new AddLibraryRejection.PathAlreadyRegistered(),
            "LibraryPathAlreadyRegisteredError",
            "filepath"));
  }

  static Stream<Arguments> libraryBusyRefusals() {
    return Stream.of(
            LibraryOperationMutation.START_LIBRARY_SCAN,
            LibraryOperationMutation.START_LIBRARY_REFRESH,
            LibraryOperationMutation.REMOVE_LIBRARY)
        .flatMap(
            mutation ->
                Stream.of(
                    Arguments.of(
                        mutation,
                        LibraryOperationKind.SCAN,
                        LibraryStatus.SCANNING,
                        "A library scan is already in progress."),
                    Arguments.of(
                        mutation,
                        LibraryOperationKind.REFRESH,
                        LibraryStatus.REFRESHING,
                        "A library refresh is already in progress."),
                    Arguments.of(
                        mutation,
                        LibraryOperationKind.SCAN,
                        LibraryStatus.STOPPING,
                        "A library scan is stopping."),
                    Arguments.of(
                        mutation,
                        LibraryOperationKind.REFRESH,
                        LibraryStatus.STOPPING,
                        "A library refresh is stopping.")));
  }

  static Stream<Arguments> notInProgressRefusals() {
    return Stream.of(
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_SCAN,
            LibraryStatus.HEALTHY,
            Optional.empty(),
            "LibraryScanNotInProgressError",
            "No library scan is in progress, so there is nothing to stop."),
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_SCAN,
            LibraryStatus.REFRESHING,
            Optional.of(LibraryOperationKind.REFRESH),
            "LibraryScanNotInProgressError",
            "No library scan is in progress, so there is nothing to stop. A library refresh is in"
                + " progress."),
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_SCAN,
            LibraryStatus.STOPPING,
            Optional.of(LibraryOperationKind.REFRESH),
            "LibraryScanNotInProgressError",
            "No library scan is in progress, so there is nothing to stop. A library refresh is"
                + " stopping."),
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_REFRESH,
            LibraryStatus.UNHEALTHY,
            Optional.empty(),
            "LibraryRefreshNotInProgressError",
            "No library refresh is in progress, so there is nothing to stop."),
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_REFRESH,
            LibraryStatus.SCANNING,
            Optional.of(LibraryOperationKind.SCAN),
            "LibraryRefreshNotInProgressError",
            "No library refresh is in progress, so there is nothing to stop. A library scan is in"
                + " progress."),
        Arguments.of(
            LibraryOperationMutation.STOP_LIBRARY_REFRESH,
            LibraryStatus.STOPPING,
            Optional.of(LibraryOperationKind.SCAN),
            "LibraryRefreshNotInProgressError",
            "No library refresh is in progress, so there is nothing to stop. A library scan is"
                + " stopping."));
  }

  /** The three library operation mutations, each selecting its result and its user errors. */
  enum LibraryOperationMutation {
    START_LIBRARY_SCAN("startLibraryScan", "library { id status }", "LibraryBusyError"),
    START_LIBRARY_REFRESH("startLibraryRefresh", "library { id status }", "LibraryBusyError"),
    STOP_LIBRARY_SCAN("stopLibraryScan", "library { id status }", "LibraryScanNotInProgressError"),
    STOP_LIBRARY_REFRESH(
        "stopLibraryRefresh", "library { id status }", "LibraryRefreshNotInProgressError"),
    REMOVE_LIBRARY("removeLibrary", "removedLibraryId", "LibraryBusyError");

    private final String fieldName;
    private final String resultSelection;
    private final String errorWithStatus;

    LibraryOperationMutation(String fieldName, String resultSelection, String errorWithStatus) {
      this.fieldName = fieldName;
      this.resultSelection = resultSelection;
      this.errorWithStatus = errorWithStatus;
    }

    String fieldName() {
      return fieldName;
    }

    // The result field is the first word of the result's selection.
    String resultField() {
      return resultSelection.split(" ")[0];
    }

    String document(String libraryId) {
      return """
          mutation {
            %s(input: { libraryId: "%s" }) {
              %s
              userErrors {
                __typename
                ... on MutationError { message }
                ... on InputMutationError { inputPath }
                ... on %s { status }
              }
            }
          }
          """
          .formatted(fieldName, libraryId, resultSelection, errorWithStatus);
    }

    @Override
    public String toString() {
      return fieldName;
    }
  }

  private Map<String, Object> payloadOf(LibraryOperationMutation mutation, String libraryId) {
    var result = dgsQueryExecutor.execute(mutation.document(libraryId));

    assertThat(result.getErrors()).isEmpty();
    Map<String, Object> data = result.getData();
    @SuppressWarnings("unchecked")
    var payload = (Map<String, Object>) data.get(mutation.fieldName());
    return payload;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> userErrorsOf(Map<String, Object> payload) {
    return (List<Map<String, Object>>) payload.get("userErrors");
  }

  private static void rejectEveryRequestWith(LibraryOperationRejection rejection) {
    FAKE_LIBRARY_OPERATION_SERVICE.rejectWith(rejection);
    FAKE_LIBRARY_MANAGEMENT_SERVICE.rejectRemovalWith(rejection);
  }

  @Nested
  @DisplayName("Paginated Items")
  class PaginatedItems {

    @Test
    @DisplayName("Should return paginated items when library queried")
    void shouldReturnPaginatedItemsWhenLibraryQueried() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Inception").titleSort("Inception").build();
      movie.setId(UUID.randomUUID());

      var page = new ListPage<>(List.of(titleRow(movie)), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaFilter.class), any(PageRequest.class)))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Movie { title } } cursor } pageInfo { hasNextPage } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Inception");
    }

    @Test
    @DisplayName("Should return series items when series library queried")
    void shouldReturnSeriesItemsWhenSeriesLibraryQueried() {
      var libraryId = UUID.randomUUID();
      var library =
          Library.builder()
              .name("TV Shows")
              .filepathUri("file:///mpool/media/shows")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.SERIES)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(libraryId);

      var series = Series.builder().title("Breaking Bad").titleSort("Breaking Bad").build();
      series.setId(UUID.randomUUID());

      var page = new ListPage<>(List.of(titleRow(series)), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(seriesService.getSeriesWithFilter(any(MediaFilter.class), any(PageRequest.class)))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Series { title } } cursor } pageInfo { hasNextPage } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Breaking Bad");
    }

    @Test
    @DisplayName("Should return error when unsupported media type in items")
    void shouldReturnErrorWhenUnsupportedMediaTypeInItems() {
      var libraryId = UUID.randomUUID();
      var library =
          Library.builder()
              .name("Other Media")
              .filepathUri("file:///mpool/media/other")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.OTHER)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId));

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().get(0).getMessage()).contains("Unsupported media type");
    }

    @Test
    @DisplayName("Should return GraphQL error when cursor is malformed")
    void shouldReturnGraphQLErrorWhenCursorIsMalformed() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, after: "not-a-valid-cursor") { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId));

      assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("Should return invalid cursor when start letter changes on non-TITLE cursor")
    void shouldReturnInvalidCursorWhenStartLetterChangesOnNonTitleCursor() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Batman").titleSort("Batman").build();
      movie.setId(UUID.randomUUID());
      var page =
          new ListPage<>(
              List.of(
                  new Row<>(
                      movie,
                      new Position(
                          new SortValue.OfInstant(Instant.parse("2026-08-04T12:00:00Z")),
                          movie.getId()))),
              false,
              true);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaFilter.class), any(PageRequest.class)))
          .thenReturn(page);

      String cursor =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 1, filter: {startLetter: A}, sort: {by: ADDED}) { edges { cursor } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].cursor");

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 1, after: "%s", filter: {startLetter: B}, sort: {by: ADDED}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId, cursor));

      assertThat(result.getErrors()).hasSize(1);
      assertThat(result.getErrors().getFirst().getExtensions())
          .containsEntry("code", "INVALID_CURSOR");
    }

    @Test
    @DisplayName("Should page backward with jump cursor when later request drops start letter")
    void shouldPageBackwardWithJumpCursorWhenLaterRequestDropsStartLetter() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var batman = Movie.builder().title("Batman").titleSort("Batman").build();
      batman.setId(UUID.randomUUID());
      var alpha = Movie.builder().title("Alpha").titleSort("Alpha").build();
      alpha.setId(UUID.randomUUID());

      var landingPage = new ListPage<>(List.of(titleRow(batman)), true, true);
      var backwardPage = new ListPage<>(List.of(titleRow(alpha)), false, true);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaFilter.class), any(PageRequest.class)))
          .thenReturn(landingPage, backwardPage);

      String cursor =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 1, filter: {startLetter: B}) { edges { cursor } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].cursor");

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(last: 1, before: "%s", filter: {}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId, cursor),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Alpha");
    }

    @Test
    @DisplayName("Should delegate sort options to movie service when sort input provided")
    void shouldDelegateSortOptionsToMovieServiceWhenSortInputProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Inception").titleSort("Inception").build();
      movie.setId(UUID.randomUUID());

      var page = new ListPage<>(List.of(titleRow(movie)), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaFilter f) ->
                      f.getSortBy() == OrderMediaBy.ADDED
                          && f.getSortDirection() == SortDirection.DESC),
              any(PageRequest.class)))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, sort: {by: ADDED, direction: DESC}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Inception");
    }

    @Test
    @DisplayName("Should delegate filter options to movie service when filter input provided")
    void shouldDelegateFilterOptionsToMovieServiceWhenFilterInputProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Filtered Movie").titleSort("Filtered Movie").build();
      movie.setId(UUID.randomUUID());

      var page = new ListPage<>(List.of(titleRow(movie)), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaFilter f) ->
                      f.getStartLetter() == AlphabetLetter.A
                          && f.getYears() != null
                          && f.getYears().contains(2024)
                          && f.getContentRatings() != null
                          && f.getContentRatings().contains("PG-13")
                          && Boolean.FALSE.equals(f.getUnmatched())),
              any(PageRequest.class)))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, filter: {startLetter: A, years: [2024], contentRatings: ["PG-13"], unmatched: false}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Filtered Movie");
    }

    @Test
    @DisplayName(
        "Should forward watchStatus filter to movie service when watchStatus provided in"
            + " GraphQL filter input")
    void shouldForwardWatchStatusFilterToMovieServiceWhenWatchStatusProvidedInGraphqlFilterInput() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("In Progress Movie").titleSort("In Progress Movie").build();
      movie.setId(UUID.randomUUID());

      var page = new ListPage<>(List.of(titleRow(movie)), false, false);
      var capturedFilter = new AtomicReference<MediaFilter>();

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaFilter.class), any(PageRequest.class)))
          .thenAnswer(
              invocation -> {
                capturedFilter.set(invocation.getArgument(0));
                return page;
              });

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, filter: {watchStatus: IN_PROGRESS}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("In Progress Movie");
      assertThat(capturedFilter.get().getWatchStatus())
          .as("watchStatus must be forwarded from GraphQL filter to MediaFilter")
          .isEqualTo(WatchStatus.IN_PROGRESS);
      assertThat(capturedFilter.get().getLibraryId())
          .as("libraryId must be forwarded from parent library to MediaFilter")
          .isEqualTo(libraryId);
    }

    @Test
    @DisplayName("Should throw with simple class name when unsupported media type in type resolver")
    void shouldThrowWithSimpleClassNameWhenUnsupportedMediaTypeInTypeResolver() {
      var resolver = new LibraryResolver(null, null, null, null, null, null);

      var unsupportedMedia = new Object();

      assertThatThrownBy(() -> resolver.resolveMedia(unsupportedMedia))
          .isInstanceOf(UnsupportedMediaTypeException.class)
          .hasMessage("Unsupported media type: Object");
    }
  }

  @Nested
  @DisplayName("Alphabet Index")
  class AlphabetIndexTests {

    @Test
    @WithAccountContext
    @DisplayName("Should require profile scope when resolving alphabet index")
    void shouldRequireProfileScopeWhenResolvingAlphabetIndex() {
      assertThatThrownBy(() -> libraryResolver.alphabetIndex(null))
          .isInstanceOf(ProfileRequiredException.class);
    }

    @Test
    @DisplayName("Should return alphabet index when library exists")
    void shouldReturnAlphabetIndexWhenLibraryExists() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var metadataA =
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(AlphabetLetter.A)
              .itemCount(5)
              .build();
      metadataA.setId(UUID.randomUUID());

      var metadataM =
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(AlphabetLetter.M)
              .itemCount(12)
              .build();
      metadataM.setId(UUID.randomUUID());

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      FAKE_LIBRARY_MANAGEMENT_SERVICE.returnAlphabetIndex(List.of(metadataA, metadataM));

      var query =
          String.format("{ library(id: \"%s\") { alphabetIndex { letter count } } }", libraryId);

      List<String> letters =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex[*].letter");
      List<Integer> counts =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex[*].count");

      assertThat(letters).containsExactly("A", "M");
      assertThat(counts).containsExactly(5, 12);
    }

    @Test
    @DisplayName("Should return empty alphabet index when no metadata")
    void shouldReturnEmptyAlphabetIndexWhenNoMetadata() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      FAKE_LIBRARY_MANAGEMENT_SERVICE.returnAlphabetIndex(List.of());

      var query =
          String.format("{ library(id: \"%s\") { alphabetIndex { letter count } } }", libraryId);

      List<Object> alphabetIndex =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex");

      assertThat(alphabetIndex).isEmpty();
    }
  }

  private static <T extends BaseCollectable<?>> Row<T> titleRow(T item) {
    var value = new SortValue.OfText(item.getTitleSort().toLowerCase(Locale.ROOT));
    return new Row<>(item, new Position(value, item.getId()));
  }

  private static final class FakeLibraryManagementService extends LibraryManagementService {

    private AuthenticatedIdentity addedIdentity;
    private RuntimeException addLibraryFailure;
    private Outcome<Library, AddLibraryRejection> addLibraryOutcome;
    private Library addedLibrary;
    private List<LibraryMetadata> alphabetIndex = List.of();
    private final List<UUID> removedLibraryIds = new ArrayList<>();
    private LibraryOperationRejection removalRejection;

    private FakeLibraryManagementService() {
      super(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public Outcome<Library, AddLibraryRejection> addLibrary(
        AuthenticatedIdentity identity, Library library) {
      if (addLibraryFailure != null) {
        throw addLibraryFailure;
      }

      addedIdentity = identity;
      addedLibrary = library;
      return addLibraryOutcome != null ? addLibraryOutcome : Outcome.accepted(library);
    }

    @Override
    public Outcome<UUID, LibraryOperationRejection> removeLibrary(
        AuthenticatedIdentity identity, UUID libraryId) {
      removedLibraryIds.add(libraryId);
      if (removalRejection != null) {
        return Outcome.rejected(removalRejection);
      }

      return Outcome.accepted(libraryId);
    }

    @Override
    public List<LibraryMetadata> getAlphabetIndex(UUID libraryId) {
      return alphabetIndex;
    }

    private void returnLibraryWhenAdded(Library library) {
      addLibraryOutcome = Outcome.accepted(library);
    }

    private void returnOutcomeWhenAdded(Outcome<Library, AddLibraryRejection> outcome) {
      addLibraryOutcome = outcome;
    }

    private void throwWhenAdded(RuntimeException exception) {
      addLibraryFailure = exception;
    }

    private AuthenticatedIdentity addedIdentity() {
      return addedIdentity;
    }

    private Library addedLibrary() {
      return addedLibrary;
    }

    private void returnAlphabetIndex(List<LibraryMetadata> metadata) {
      alphabetIndex = metadata;
      FAKE_COUNTS.deleteAll();
      FAKE_COUNTS.saveAll(metadata);
    }

    private List<UUID> removedLibraryIds() {
      return List.copyOf(removedLibraryIds);
    }

    private void rejectRemovalWith(LibraryOperationRejection rejection) {
      removalRejection = rejection;
    }

    private void reset() {
      addedIdentity = null;
      addLibraryFailure = null;
      addLibraryOutcome = null;
      addedLibrary = null;
      alphabetIndex = List.of();
      FAKE_COUNTS.deleteAll();
      removedLibraryIds.clear();
      removalRejection = null;
    }
  }

  private static final class FakeLibraryOperationService extends LibraryOperationService {

    private final List<UUID> startedLibraryIds = new ArrayList<>();
    private final List<StopRequest> stopRequests = new ArrayList<>();
    private RefreshRequest refreshRequest;
    private RuntimeException startFailure;
    private LibraryOperationRejection rejection;
    private LibraryOperationStopRejection stopRejection;

    private FakeLibraryOperationService() {
      super(null, null, null, null, null, null);
    }

    @Override
    public Outcome<Library, LibraryOperationRejection> startLibraryScan(
        AuthenticatedIdentity identity, UUID libraryId) {
      return start(libraryId, LibraryStatus.SCANNING);
    }

    @Override
    public Outcome<Library, LibraryOperationRejection> startLibraryRefresh(
        AuthenticatedIdentity identity, UUID libraryId, ImageRefreshMode imageRefreshMode) {
      refreshRequest = new RefreshRequest(libraryId, imageRefreshMode);
      return start(libraryId, LibraryStatus.REFRESHING);
    }

    @Override
    public Outcome<Library, LibraryOperationStopRejection> stopLibraryScan(
        AuthenticatedIdentity identity, UUID libraryId) {
      return stop(new StopRequest(libraryId, LibraryOperationKind.SCAN));
    }

    @Override
    public Outcome<Library, LibraryOperationStopRejection> stopLibraryRefresh(
        AuthenticatedIdentity identity, UUID libraryId) {
      return stop(new StopRequest(libraryId, LibraryOperationKind.REFRESH));
    }

    private Outcome<Library, LibraryOperationStopRejection> stop(StopRequest request) {
      stopRequests.add(request);
      if (stopRejection != null) {
        return Outcome.rejected(stopRejection);
      }

      var library = Library.builder().status(LibraryStatus.STOPPING).build();
      library.setId(request.libraryId());
      return Outcome.accepted(library);
    }

    private Outcome<Library, LibraryOperationRejection> start(
        UUID libraryId, LibraryStatus busyStatus) {
      startedLibraryIds.add(libraryId);
      if (startFailure != null) {
        throw startFailure;
      }

      if (rejection != null) {
        return Outcome.rejected(rejection);
      }

      var library = Library.builder().status(busyStatus).build();
      library.setId(libraryId);
      return Outcome.accepted(library);
    }

    private List<UUID> startedLibraryIds() {
      return List.copyOf(startedLibraryIds);
    }

    private RefreshRequest refreshRequest() {
      return refreshRequest;
    }

    private void throwWhenStarted(RuntimeException exception) {
      startFailure = exception;
    }

    private void rejectWith(LibraryOperationRejection libraryOperationRejection) {
      rejection = libraryOperationRejection;
    }

    private void rejectStopsWith(LibraryOperationStopRejection libraryOperationStopRejection) {
      stopRejection = libraryOperationStopRejection;
    }

    private List<StopRequest> stopRequests() {
      return List.copyOf(stopRequests);
    }

    private void reset() {
      startedLibraryIds.clear();
      stopRequests.clear();
      refreshRequest = null;
      startFailure = null;
      rejection = null;
      stopRejection = null;
    }
  }

  private record RefreshRequest(UUID libraryId, ImageRefreshMode imageRefreshMode) {}

  private record StopRequest(UUID libraryId, LibraryOperationKind kind) {}

  private Library buildMovieLibrary(UUID libraryId) {
    var library =
        Library.builder()
            .name("Movies")
            .filepathUri("file:///mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);
    return library;
  }
}
