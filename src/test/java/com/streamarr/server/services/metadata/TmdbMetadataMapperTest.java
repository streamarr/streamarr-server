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
    @DisplayName("Should include cast when profile paths are present")
    void shouldIncludeCastWhenProfilePathsArePresent() {
      var cast =
          List.of(TmdbCredit.builder().id(1).name("Actor A").profilePath("/profile1.jpg").build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(cast, List.of());

      assertThat(sources)
          .containsEntry("1", List.of(new TmdbImageSource(ImageType.PROFILE, "/profile1.jpg")));
    }

    @Test
    @DisplayName("Should skip cast when profile path is blank")
    void shouldSkipCastWhenProfilePathIsBlank() {
      var cast = List.of(TmdbCredit.builder().id(2).name("Actor B").profilePath("").build());

      var sources = TmdbMetadataMapper.buildPersonImageSources(cast, List.of());

      assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("Should include only crew members when job is Director")
    void shouldIncludeOnlyCrewMembersWhenJobIsDirector() {
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
    @DisplayName("Should combine cast and director sources when both are present")
    void shouldCombineCastAndDirectorSourcesWhenBothArePresent() {
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
    @DisplayName("Should include company when logo path is present")
    void shouldIncludeCompanyWhenLogoPathIsPresent() {
      var companies =
          List.of(
              TmdbProductionCompany.builder().id(5).name("Studio").logoPath("/logo.png").build());

      var sources = TmdbMetadataMapper.buildCompanyImageSources(companies);

      assertThat(sources)
          .containsEntry("5", List.of(new TmdbImageSource(ImageType.LOGO, "/logo.png")));
    }

    @Test
    @DisplayName("Should skip company when logo path is absent")
    void shouldSkipCompanyWhenLogoPathIsAbsent() {
      var companies = List.of(TmdbProductionCompany.builder().id(6).name("No Logo Inc").build());

      var sources = TmdbMetadataMapper.buildCompanyImageSources(companies);

      assertThat(sources).isEmpty();
    }
  }

  @Nested
  @DisplayName("Map Directors")
  class MapDirectors {

    @Test
    @DisplayName("Should return only directors when mapping crew list")
    void shouldReturnOnlyDirectorsWhenMappingCrewList() {
      var crew =
          List.of(
              TmdbCredit.builder().id(10).name("Director D").job("Director").build(),
              TmdbCredit.builder().id(11).name("Producer P").job("Producer").build());

      var directors = TmdbMetadataMapper.mapDirectors(crew);

      assertThat(directors).extracting(Person::getName).containsExactly("Director D");
    }
  }

  @Test
  @DisplayName("Should map cast fields when converting from external type")
  void shouldMapCastFieldsWhenConvertingFromExternalType() {
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
  @DisplayName("Should map company fields when converting from external type")
  void shouldMapCompanyFieldsWhenConvertingFromExternalType() {
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
  @DisplayName("Should map genre fields when converting from external type")
  void shouldMapGenreFieldsWhenConvertingFromExternalType() {
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
