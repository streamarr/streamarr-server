package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.library.AddLibraryRejection;
import com.streamarr.server.services.library.LibraryAdministrationService;
import com.streamarr.server.services.library.LibraryManagementService;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.support.security.WithAccountContext;
import com.streamarr.server.support.security.WithProfileContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      LibraryResolver.class,
      LibraryAdministrationService.class,
      PaginationService.class,
      CursorUtil.class,
      CursorValidator.class,
      RelayConnectionAdapter.class,
      JacksonAutoConfiguration.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Library Resolver Tests")
class LibraryResolverTest {

  private static final FakeLibraryManagementService FAKE_LIBRARY_MANAGEMENT_SERVICE =
      new FakeLibraryManagementService();

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @Autowired private LibraryResolver libraryResolver;

  @Autowired private FakeAuthorizationDecider authorizationDecider;

  @Autowired private AuthorizationService authorizationService;

  @MockitoBean private ProfileRepository profileRepository;

  @MockitoBean private AccountProfileRepository accountProfileRepository;

  @MockitoBean private LibraryRepository libraryRepository;

  @TestBean private LibraryManagementService libraryManagementService;

  @MockitoBean private MovieService movieService;

  @MockitoBean private SeriesService seriesService;

  static LibraryManagementService libraryManagementService() {
    return FAKE_LIBRARY_MANAGEMENT_SERVICE;
  }

  @BeforeEach
  void resetLibraryManagementService() {
    FAKE_LIBRARY_MANAGEMENT_SERVICE.reset();
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
  @WithProfileContext(role = AccountRole.ADMIN)
  class LibraryMutations {

    @AfterEach
    void allowAgain() {
      authorizationDecider.allowAll();
    }

    @Test
    @DisplayName("Should return true when scanLibrary called with valid ID")
    void shouldReturnTrueWhenScanLibraryCalledWithValidId() {
      var libraryId = UUID.randomUUID();

      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { scanLibrary(id: \"%s\") }", libraryId), "data.scanLibrary");

      assertThat(result).isTrue();
      assertThat(authorizationDecider.recordedIntents())
          .contains(new Intent.ScanLibrary(libraryId));
    }

    @Test
    @DisplayName("Should return FORBIDDEN with no data when authorization denies")
    void shouldReturnForbiddenWithNoDataWhenAuthorizationDenies() {
      authorizationDecider.denyAll();

      var result =
          dgsQueryExecutor.execute(
              String.format("mutation { scanLibrary(id: \"%s\") }", UUID.randomUUID()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(error -> assertThat(error.getExtensions()).containsEntry("code", "FORBIDDEN"));
      // Boolean! cannot be null, so the denial nulls the whole data envelope.
      assertThat(result.<Map<String, Object>>getData()).isNull();
    }

    @Test
    @DisplayName("Should return AUTHORIZATION_UNAVAILABLE when no decision could be made")
    void shouldReturnAuthorizationUnavailableWhenNoDecisionCouldBeMade() {
      authorizationDecider.failWith(Decision.FailureCause.ENGINE_FAILURE);

      var result =
          dgsQueryExecutor.execute(
              String.format("mutation { removeLibrary(id: \"%s\") }", UUID.randomUUID()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(
              error -> {
                assertThat(error.getExtensions())
                    .containsEntry("code", "AUTHORIZATION_UNAVAILABLE");
                assertThat(error.getMessage())
                    .contains("Authorization is temporarily unavailable.");
              });
      assertThat(result.<Map<String, Object>>getData()).isNull();
    }

    @Test
    @DisplayName("Should return true when refreshLibrary called with valid ID")
    void shouldReturnTrueWhenRefreshLibraryCalledWithValidId() {
      var libraryId = UUID.randomUUID();

      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { refreshLibrary(id: \"%s\") }", libraryId),
              "data.refreshLibrary");

      assertThat(result).isTrue();
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.refreshRequest())
          .isEqualTo(new RefreshRequest(libraryId, ImageRefreshMode.PRESERVE));
    }

    @Test
    @DisplayName(
        "Should pass explicit image refresh mode when refreshLibrary mutation specifies it")
    void shouldPassExplicitImageRefreshModeWhenRefreshLibraryMutationSpecifiesIt() {
      var libraryId = UUID.randomUUID();

      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "mutation { refreshLibrary(id: \"%s\", imageRefreshMode: REFRESH_IF_CHANGED) }",
                  libraryId),
              "data.refreshLibrary");

      assertThat(result).isTrue();
      assertThat(FAKE_LIBRARY_MANAGEMENT_SERVICE.refreshRequest())
          .isEqualTo(new RefreshRequest(libraryId, ImageRefreshMode.REFRESH_IF_CHANGED));
    }

    @Test
    @DisplayName("Should reject image refresh mode when refreshLibrary mutation receives null")
    void shouldRejectImageRefreshModeWhenRefreshLibraryMutationReceivesNull() {
      var result =
          dgsQueryExecutor.execute(
              String.format(
                  "mutation { refreshLibrary(id: \"%s\", imageRefreshMode: null) }",
                  UUID.randomUUID()));

      assertThat(result.getErrors())
          .singleElement()
          .satisfies(error -> assertThat(error.getMessage()).contains("imageRefreshMode"));
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
                  new AddLibraryRejection.NameRequired(),
                  new AddLibraryRejection.PathRequired())));

      List<String> typenames =
          dgsQueryExecutor.executeAndExtractJsonPath(
              ADD_LIBRARY_MUTATION, "data.addLibrary.userErrors[*].__typename");

      assertThat(typenames).containsExactly("LibraryNameRequiredError", "LibraryPathRequiredError");
    }

    @Test
    @DisplayName("Should return true when removeLibrary called with valid ID")
    void shouldReturnTrueWhenRemoveLibraryCalledWithValidId() {
      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { removeLibrary(id: \"%s\") }", UUID.randomUUID()),
              "data.removeLibrary");

      assertThat(result).isTrue();
    }

    @ParameterizedTest(name = "Should return error when {0} called with invalid ID")
    @MethodSource("com.streamarr.server.graphql.resolvers.LibraryResolverTest#invalidIdOperations")
    @DisplayName("Should return error when operation called with invalid ID")
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    void shouldReturnErrorWhenCalledWithInvalidId(String operationName, String query) {
      var result = dgsQueryExecutor.execute(query);

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
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

  static Stream<Arguments> invalidIdOperations() {
    return Stream.of(
        Arguments.of("library", "{ library(id: \"not-a-uuid\") { name } }"),
        Arguments.of("refreshLibrary", "mutation { refreshLibrary(id: \"not-a-uuid\") }"),
        Arguments.of("removeLibrary", "mutation { removeLibrary(id: \"not-a-uuid\") }"));
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

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Inception")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

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

      var page = new MediaPage<>(List.of(new PageItem<>(series, "Breaking Bad")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(seriesService.getSeriesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

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
    @DisplayName("Should return GraphQL error when TITLE cursor is missing sort value")
    void shouldReturnGraphQLErrorWhenTitleCursorIsMissingSortValue() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Batman").titleSort("Batman").build();
      movie.setId(UUID.randomUUID());
      var page = new MediaPage<>(List.of(new PageItem<>(movie, null)), false, true);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

      String cursor =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 1) { edges { cursor } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].cursor");

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 1, after: "%s") { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId, cursor));

      assertThat(result.getErrors()).hasSize(1);
      assertThat(result.getErrors().getFirst().getMessage())
          .contains("Cursor sort value is required for TITLE sort");
    }

    @Test
    @DisplayName("Should return GraphQL error when start letter changes on non-TITLE cursor")
    void shouldReturnGraphQLErrorWhenStartLetterChangesOnNonTitleCursor() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Batman").titleSort("Batman").build();
      movie.setId(UUID.randomUUID());
      var page =
          new MediaPage<>(
              List.of(new PageItem<>(movie, Instant.parse("2026-08-04T12:00:00Z"))), false, true);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

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
      assertThat(result.getErrors().getFirst().getMessage()).contains("startLetter");
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

      var landingPage = new MediaPage<>(List.of(new PageItem<>(batman, "Batman")), true, true);
      var backwardPage = new MediaPage<>(List.of(new PageItem<>(alpha, "Alpha")), false, true);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class)))
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

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Inception")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaPaginationOptions opts) -> {
                    var f = opts.getMediaFilter();
                    return f.getSortBy() == OrderMediaBy.ADDED
                        && f.getSortDirection() == SortOrder.DESC;
                  })))
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

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Filtered Movie")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaPaginationOptions opts) -> {
                    var f = opts.getMediaFilter();
                    return f.getStartLetter() == AlphabetLetter.A
                        && f.getYears() != null
                        && f.getYears().contains(2024)
                        && f.getContentRatings() != null
                        && f.getContentRatings().contains("PG-13")
                        && Boolean.FALSE.equals(f.getUnmatched());
                  })))
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

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "In Progress Movie")), false, false);
      var capturedOptions = new AtomicReference<MediaPaginationOptions>();

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class)))
          .thenAnswer(
              invocation -> {
                capturedOptions.set(invocation.getArgument(0));
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
      assertThat(capturedOptions.get())
          .as(
              "MovieService must receive the MediaPaginationOptions produced from the GraphQL input")
          .isNotNull();
      assertThat(capturedOptions.get().getMediaFilter().getWatchStatus())
          .as("watchStatus must be forwarded from GraphQL filter to MediaFilter")
          .isEqualTo(WatchStatus.IN_PROGRESS);
      assertThat(capturedOptions.get().getMediaFilter().getLibraryId())
          .as("libraryId must be forwarded from parent library to MediaFilter")
          .isEqualTo(libraryId);
    }

    @Test
    @DisplayName("Should throw with simple class name when unsupported media type in type resolver")
    void shouldThrowWithSimpleClassNameWhenUnsupportedMediaTypeInTypeResolver() {
      var resolver =
          new LibraryResolver(null, null, null, null, null, null, null, null, null, null);

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

  private static final class FakeLibraryManagementService extends LibraryManagementService {

    private AuthenticatedIdentity addedIdentity;
    private RuntimeException addLibraryFailure;
    private Outcome<Library, AddLibraryRejection> addLibraryOutcome;
    private Library addedLibrary;
    private List<LibraryMetadata> alphabetIndex = List.of();
    private RefreshRequest refreshRequest;

    private FakeLibraryManagementService() {
      super(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          new MutexFactoryProvider(),
          null,
          null,
          null,
          null);
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
    public void removeLibrary(AuthenticatedIdentity identity, UUID libraryId) {
      assertThat(libraryId).isNotNull();
    }

    @Override
    public void triggerAsyncScan(UUID libraryId) {
      assertThat(libraryId).isNotNull();
    }

    @Override
    public void triggerAsyncRefresh(UUID libraryId, ImageRefreshMode imageRefreshMode) {
      refreshRequest = new RefreshRequest(libraryId, imageRefreshMode);
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
    }

    private RefreshRequest refreshRequest() {
      return refreshRequest;
    }

    private void reset() {
      addedIdentity = null;
      addLibraryFailure = null;
      addLibraryOutcome = null;
      addedLibrary = null;
      alphabetIndex = List.of();
      refreshRequest = null;
    }
  }

  private record RefreshRequest(UUID libraryId, ImageRefreshMode imageRefreshMode) {}

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
