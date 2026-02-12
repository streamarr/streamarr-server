package com.streamarr.server.services.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.tmdb.TmdbCredit;
import com.streamarr.server.services.metadata.tmdb.TmdbGenre;
import com.streamarr.server.services.metadata.tmdb.TmdbProductionCompany;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("TMDB Metadata Mapper Tests")
class TmdbMetadataMapperTest {

  @Nested
  @DisplayName("Build Poster and Backdrop Sources")
  class BuildPosterAndBackdropSources {

    @Test
    @DisplayName("Should return poster and backdrop when both paths are present")
    void shouldReturnPosterAndBackdropWhenBothPathsArePresent() {
      var sources =
          TmdbMetadataMapper.buildPosterAndBackdropSources("/poster.jpg", "/backdrop.jpg");

      assertThat(sources)
          .containsExactly(
              new TmdbImageSource(ImageType.POSTER, "/poster.jpg"),
              new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg"));
    }

    @Test
    @DisplayName("Should return only poster when backdrop is blank")
    void shouldReturnOnlyPosterWhenBackdropIsBlank() {
      var sources = TmdbMetadataMapper.buildPosterAndBackdropSources("/poster.jpg", "");

      assertThat(sources).containsExactly(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    }

    @Test
    @DisplayName("Should return only backdrop when poster is blank")
    void shouldReturnOnlyBackdropWhenPosterIsBlank() {
      var sources = TmdbMetadataMapper.buildPosterAndBackdropSources(null, "/backdrop.jpg");

      assertThat(sources).containsExactly(new TmdbImageSource(ImageType.BACKDROP, "/backdrop.jpg"));
    }

    @Test
    @DisplayName("Should return empty list when both paths are blank")
    void shouldReturnEmptyListWhenBothPathsAreBlank() {
      var sources = TmdbMetadataMapper.buildPosterAndBackdropSources("", null);

      assertThat(sources).isEmpty();
    }
  }

  @Nested
  @DisplayName("Build Person Image Sources")
  class BuildPersonImageSources {

    @Test
    @DisplayName("Should include cast with profile paths")
    void shouldIncludeCastWithProfilePaths() {
      var cast =
          List.of(TmdbCredit.builder().id(1).name("Actor A").profilePath("/profile1.jpg").build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(cast, List.of());

      assertThat(sources)
          .containsEntry("1", List.of(new TmdbImageSource(ImageType.PROFILE, "/profile1.jpg")));
    }

    @Test
    @DisplayName("Should skip cast with blank profile path")
    void shouldSkipCastWithBlankProfilePath() {
      var cast = List.of(TmdbCredit.builder().id(2).name("Actor B").profilePath("").build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(cast, List.of());

      assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("Should include crew with Director job only")
    void shouldIncludeCrewWithDirectorJobOnly() {
      var crew =
          List.of(
              TmdbCredit.builder()
                  .id(10)
                  .name("Director D")
                  .job("Director")
                  .profilePath("/dir.jpg")
                  .build(),
              TmdbCredit.builder()
                  .id(11)
                  .name("Writer W")
                  .job("Writer")
                  .profilePath("/writer.jpg")
                  .build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(List.of(), crew);

      assertThat(sources).containsOnlyKeys("10");
    }

    @Test
    @DisplayName("Should combine cast and director sources")
    void shouldCombineCastAndDirectorSources() {
      var cast =
          List.of(TmdbCredit.builder().id(1).name("Actor").profilePath("/actor.jpg").build());
      var crew =
          List.of(
              TmdbCredit.builder()
                  .id(10)
                  .name("Director")
                  .job("Director")
                  .profilePath("/dir.jpg")
                  .build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(cast, crew);

      assertThat(sources).containsOnlyKeys("1", "10");
    }
  }

  @Nested
  @DisplayName("Build Company Image Sources")
  class BuildCompanyImageSources {

    @Test
    @DisplayName("Should include company with logo path")
    void shouldIncludeCompanyWithLogoPath() {
      var companies =
          List.of(
              TmdbProductionCompany.builder().id(5).name("Studio").logoPath("/logo.png").build());

      var sources = TmdbMetadataMapper.buildCompanyImageSources(companies);

      assertThat(sources)
          .containsEntry("5", List.of(new TmdbImageSource(ImageType.LOGO, "/logo.png")));
    }

    @Test
    @DisplayName("Should skip company without logo path")
    void shouldSkipCompanyWithoutLogoPath() {
      var companies = List.of(TmdbProductionCompany.builder().id(6).name("No Logo Inc").build());

      var sources = TmdbMetadataMapper.buildCompanyImageSources(companies);

      assertThat(sources).isEmpty();
    }
  }

  @Nested
  @DisplayName("Map Directors")
  class MapDirectors {

    @Test
    @DisplayName("Should return only directors from crew list")
    void shouldReturnOnlyDirectorsFromCrewList() {
      var crew =
          List.of(
              TmdbCredit.builder().id(10).name("Director D").job("Director").build(),
              TmdbCredit.builder().id(11).name("Producer P").job("Producer").build());

      var directors = TmdbMetadataMapper.mapDirectors(crew);

      assertThat(directors).extracting(Person::getName).containsExactly("Director D");
    }
  }

  @Test
  @DisplayName("Should map cast fields from external to domain type")
  void shouldMapCastFieldsFromExternalToDomainType() {
    var cast = List.of(TmdbCredit.builder().id(1).name("Actor A").build());

    var result = TmdbMetadataMapper.mapCast(cast);

    assertThat(result)
        .singleElement()
        .satisfies(
            person -> {
              assertThat(person.getSourceId()).isEqualTo("1");
              assertThat(person.getName()).isEqualTo("Actor A");
            });
  }

  @Test
  @DisplayName("Should map company fields from external to domain type")
  void shouldMapCompanyFieldsFromExternalToDomainType() {
    var companies = List.of(TmdbProductionCompany.builder().id(5).name("Studio").build());

    var result = TmdbMetadataMapper.mapCompanies(companies);

    assertThat(result)
        .singleElement()
        .satisfies(
            company -> {
              assertThat(company.getSourceId()).isEqualTo("5");
              assertThat(company.getName()).isEqualTo("Studio");
            });
  }

  @Test
  @DisplayName("Should map genre fields from external to domain type")
  void shouldMapGenreFieldsFromExternalToDomainType() {
    var genres = List.of(new TmdbGenre(28, "Action"));

    var result = TmdbMetadataMapper.mapGenres(genres);

    assertThat(result)
        .singleElement()
        .satisfies(
            genre -> {
              assertThat(genre.getSourceId()).isEqualTo("28");
              assertThat(genre.getName()).isEqualTo("Action");
            });
  }
}
