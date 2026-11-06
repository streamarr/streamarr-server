package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.dataloaders.LibraryDataLoader;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.library.LibraryQueryService;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      LibraryOfItemFieldResolver.class,
      LibraryDataLoader.class,
      MovieResolver.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Library Of Item Field Resolver Tests")
class LibraryOfItemFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private UserAccountRepository userAccountRepository;
  @MockitoBean private MovieService movieService;
  @MockitoBean private LibraryQueryService libraryQueryService;

  @Test
  @DisplayName(
      "Should fail only the movie whose library is missing when another movie's library resolves")
  void shouldFailOnlyTheMovieWhoseLibraryIsMissingWhenAnotherMoviesLibraryResolves() {
    var found = libraryNamed("Films");
    var missing = libraryNamed("Removed Films");
    var foundMovie = servedMovieIn(found);
    var orphan = servedMovieIn(missing);
    when(libraryQueryService.findByIds(anyCollection()))
        .thenAnswer(read -> onlyRequested(read.getArgument(0), List.of(found)));

    var result =
        dgsQueryExecutor.execute(
            """
            {
              found: movie(id: "%s") { library { name } }
              orphan: movie(id: "%s") { library { name } }
            }
            """
                .formatted(foundMovie.getId(), orphan.getId()));

    Map<String, Object> data = result.getData();
    assertThat(data)
        .containsEntry("found", Map.of("library", Map.of("name", "Films")))
        .containsEntry("orphan", null);
    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> {
              assertThat(error.getPath()).first().isEqualTo("orphan");
              assertThat(error.getMessage()).contains("Library not found: " + missing.getId());
            });
  }

  private static Library libraryNamed(String name) {
    return LibraryFixtureCreator.buildFakeLibraryWithId().toBuilder().name(name).build();
  }

  private static List<Library> onlyRequested(
      Collection<UUID> requestedIds, List<Library> libraries) {
    return libraries.stream().filter(library -> requestedIds.contains(library.getId())).toList();
  }

  private Movie servedMovieIn(Library library) {
    var movie = Movie.builder().title("Movie in " + library.getName()).library(library).build();
    movie.setId(UUID.randomUUID());
    when(movieService.findById(movie.getId())).thenReturn(Optional.of(movie));
    return movie;
  }
}
