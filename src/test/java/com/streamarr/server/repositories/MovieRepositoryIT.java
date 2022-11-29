package com.streamarr.server.repositories;

import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.media.MovieRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class MovieRepositoryIT {

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Container
    private final static PostgreSQLContainer<?> postgresqlContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.3-alpine"))
        .withDatabaseName("streamarr")
        .withUsername("foo")
        .withPassword("secret");

    private Library savedLibrary;

    @DynamicPropertySource
    static void sqlProperties(DynamicPropertyRegistry registry) {
        postgresqlContainer.start();

        registry.add("spring.datasource.url", postgresqlContainer::getJdbcUrl);
        registry.add("spring.datasource.username", postgresqlContainer::getUsername);
        registry.add("spring.datasource.password", postgresqlContainer::getPassword);
    }

    @BeforeAll
    public void setup() {
        var fakeLibrary = LibraryFixtureCreator.buildFakeLibrary();

        savedLibrary = libraryRepository.save(fakeLibrary);
    }

    @Test
    @DisplayName("Should save a Movie with it's MediaFile when no existing Movie in the database.")
    void shouldSaveMovieWithMediaFile() {
        var file = MediaFile.builder()
            .libraryId(savedLibrary.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("a-wonderful-test-[1080p].mkv")
            .filepath("/root/a-wonderful-test-[1080p].mkv")
            .build();

        var movie = movieRepository.saveAndFlush(Movie.builder()
            .title("A Wonderful Test")
            .files(Set.of(file))
            .libraryId(savedLibrary.getId())
            .build());

        assertThat(movie.getFiles()).hasSize(1);
    }

    @Test
    @DisplayName("Should save a Movie with with it's external identifier information when no existing Movie in the database.")
    @Transactional
    void shouldSaveMovieWithExternalIdentifier() {
        var fakeExternalId = ExternalIdentifier.builder()
            .externalId("123")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

        movieRepository.saveAndFlush(Movie.builder()
            .title("A Wonderful Test")
            .externalIds(Set.of(fakeExternalId))
            .libraryId(savedLibrary.getId())
            .build());

        var result = movieRepository.findByTmdbId(fakeExternalId.getExternalId());

        assertThat(result).isPresent();
        assertThat(result.get().getExternalIds()).hasSize(1);
    }
}
