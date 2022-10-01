package com.streamarr.server.repositories;

import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MovieRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@Testcontainers
public class MovieRepositoryIT {

    @Autowired
    private MovieRepository movieRepository;

    @Container
    static PostgreSQLContainer<?> postgresqlContainer = new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.3-alpine"))
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

    @Test
    @DisplayName("Should save a Movie with it's MediaFile when no existing Movie in the database.")
    @Transactional
    void shouldSaveMovieWithMediaFile() {

        var libraryId = UUID.fromString("41b306af-59d0-43f0-af6d-d967592aeb18");

        var file = MediaFile.builder()
            .libraryId(libraryId)
            .status(MediaFileStatus.MATCHED)
            .filename("a-wonderful-test-[1080p].mkv")
            .filepath("/root/a-wonderful-test-[1080p].mkv")
            .build();

        var movie = movieRepository.saveAndFlush(Movie.builder()
            .title("A Wonderful Test")
            .tmdbId("123")
            .files(Set.of(file))
            .libraryId(libraryId) // TODO: This should probably be generated in the test...
            .build());

        assertThat(movie.getFiles()).hasSize(1);
    }
}
