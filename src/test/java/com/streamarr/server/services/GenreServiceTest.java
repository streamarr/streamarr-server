package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.fakes.FakeGenreRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Genre Service Tests")
class GenreServiceTest {

  private FakeGenreRepository genreRepository;
  private GenreService genreService;

  @BeforeEach
  void setUp() {
    genreRepository = new FakeGenreRepository();
    genreService = new GenreService(genreRepository, new MutexFactoryProvider());
  }

  @Test
  @DisplayName("Should create new genre when source ID not found")
  void shouldCreateNewGenreWhenSourceIdNotFound() {
    var genre = Genre.builder().name("Action").sourceId("genre-28").build();

    var result = genreService.getOrCreateGenres(Set.of(genre));

    assertThat(result).hasSize(1);
    var saved = result.iterator().next();
    assertThat(saved.getName()).isEqualTo("Action");
    assertThat(saved.getSourceId()).isEqualTo("genre-28");
    assertThat(saved.getId()).isNotNull();
    assertThat(genreRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing genre when source ID already exists")
  void shouldUpdateExistingGenreWhenSourceIdAlreadyExists() {
    var existing =
        genreRepository.save(Genre.builder().name("Old Name").sourceId("genre-28").build());

    var updated = Genre.builder().name("New Name").sourceId("genre-28").build();

    var result = genreService.getOrCreateGenres(Set.of(updated));

    assertThat(result).hasSize(1);
    var returned = result.iterator().next();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(genreRepository.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should update existing genre when batch contains duplicate source ID")
  void shouldUpdateExistingGenreWhenBatchContainsDuplicateSourceId() {
    var existing =
        genreRepository.save(Genre.builder().name("Existing Genre").sourceId("existing-1").build());

    var updatedExisting = Genre.builder().name("Updated Genre").sourceId("existing-1").build();
    var brandNew = Genre.builder().name("Brand New Genre").sourceId("new-1").build();

    var result = genreService.getOrCreateGenres(Set.of(updatedExisting, brandNew));

    var returnedExisting =
        result.stream().filter(g -> "existing-1".equals(g.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Genre");
  }

  @Test
  @DisplayName("Should create new genre when batch contains unknown source ID")
  void shouldCreateNewGenreWhenBatchContainsUnknownSourceId() {
    genreRepository.save(Genre.builder().name("Existing Genre").sourceId("existing-1").build());

    var updatedExisting = Genre.builder().name("Updated Genre").sourceId("existing-1").build();
    var brandNew = Genre.builder().name("Brand New Genre").sourceId("new-1").build();

    var result = genreService.getOrCreateGenres(Set.of(updatedExisting, brandNew));

    var returnedNew =
        result.stream().filter(g -> "new-1".equals(g.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Genre");
    assertThat(returnedNew.getId()).isNotNull();
    assertThat(genreRepository.count()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should throw when genre source ID is null")
  void shouldThrowWhenGenreSourceIdIsNull() {
    var genre = Genre.builder().name("Action").sourceId(null).build();
    var genres = Set.of(genre);

    assertThatThrownBy(() -> genreService.getOrCreateGenres(genres))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should return empty set when input is null")
  void shouldReturnEmptySetWhenInputIsNull() {
    var result = genreService.getOrCreateGenres(null);

    assertThat(result).isEmpty();
  }
}
