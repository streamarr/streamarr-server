package com.streamarr.server.services;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.utils.FakeLibraryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MovieServiceIT {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private MovieService movieService;

    @Container
    private final static PostgreSQLContainer<?> postgresqlContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.3-alpine"))
        .withDatabaseName("streamarr")
        .withUsername("foo")
        .withPassword("secret");

    @DynamicPropertySource
    static void sqlProperties(DynamicPropertyRegistry registry) {
        postgresqlContainer.start();

        registry.add("spring.datasource.url", postgresqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresqlContainer::getUsername);
        registry.add("spring.datasource.password", postgresqlContainer::getPassword);
    }

    @BeforeAll
    public void setup() {

        var fakeLibrary = FakeLibraryHelper.buildFakeLibrary();

        var savedLibrary = libraryRepository.saveAndFlush(fakeLibrary);

        var fakeMovie1 = Movie.builder()
            .title("fakeMovie")
            .libraryId(savedLibrary.getId())
            .build();

        var fakeMovie2 = Movie.builder()
            .title("fakeMovie")
            .libraryId(savedLibrary.getId())
            .build();

        movieRepository.saveAllAndFlush(List.of(fakeMovie1, fakeMovie2));
    }

    @Test
    @DisplayName("Should limit first set of results to one when given 'first' argument and no cursor")
    void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

        var movies = movieService.getMoviesWithFilter(1, null, 0, null, null);

        assertThat(movies.getEdges().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
    void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

        var firstPageMovies = movieService.getMoviesWithFilter(1, null, 0, null, null);

        var endCursor = firstPageMovies.getPageInfo().getEndCursor();

        assertThat(firstPageMovies.getEdges().size()).isEqualTo(1);

        var secondPageMovies = movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, null);

        assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

        var movie1 = firstPageMovies.getEdges().get(0);
        var movie2 = secondPageMovies.getEdges().get(0);

        assertThat(movie1.getNode().getId()).isNotEqualByComparingTo(movie2.getNode().getId());
    }

    @Test
    @DisplayName("Should paginate backward once, after getting first two results when given 'last' and 'before' arguments")
    void shouldPaginateBackwardWhenGivenLastAndCursor() {

        var firstPageMovies = movieService.getMoviesWithFilter(2, null, 0, null, null);

        var endCursor = firstPageMovies.getPageInfo().getEndCursor();

        assertThat(firstPageMovies.getEdges().size()).isEqualTo(2);

        var secondPageMovies = movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), null);

        assertThat(secondPageMovies.getEdges().size()).isEqualTo(1);

        var movie1 = firstPageMovies.getEdges().get(0);
        var movie2 = secondPageMovies.getEdges().get(0);

        assertThat(movie1.getNode().getId()).isEqualTo(movie2.getNode().getId());
    }

}
