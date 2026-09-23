package com.streamarr.server.services.library;

import static com.streamarr.server.fixtures.MetadataFixture.found;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.NotFound;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Date-Based Episode Resolver Tests")
class DateBasedEpisodeResolverTest {

  private final FakeSeriesMetadataProvider fakeProvider = new FakeSeriesMetadataProvider();
  private final SeriesMetadataProviderResolver resolver =
      new SeriesMetadataProviderResolver(List.of(fakeProvider));
  private final DateBasedEpisodeResolver dateResolver = new DateBasedEpisodeResolver(resolver);

  private static final Library LIBRARY =
      Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();

  @Test
  @DisplayName("Should resolve episode when date matches in current year")
  void shouldResolveEpisodeWhenDateMatchesInCurrentYear() {
    fakeProvider.addSeasonMapping("12345", 2025, 10);
    fakeProvider.addSeasonDetails(
        "12345",
        10,
        SeasonDetails.builder()
            .name("Season 10")
            .seasonNumber(10)
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(42)
                        .name("Episode 42")
                        .airDate(LocalDate.of(2025, 11, 25))
                        .imageSources(List.of())
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(43)
                        .name("Episode 43")
                        .airDate(LocalDate.of(2025, 11, 26))
                        .imageSources(List.of())
                        .build()))
            .imageSources(List.of())
            .build());

    var result = dateResolver.resolve(LIBRARY, "12345", LocalDate.of(2025, 11, 25));
    assertThat(found(result).seasonNumber()).isEqualTo(10);
    assertThat(found(result).episodeNumber()).isEqualTo(42);
  }

  @Test
  @DisplayName("Should fall back to year minus one when broadcast-year crossover detected")
  void shouldFallBackToYearMinusOneWhenBroadcastYearCrossoverDetected() {
    fakeProvider.addSeasonMapping("12345", 2024, 5);
    fakeProvider.addSeasonDetails(
        "12345",
        5,
        SeasonDetails.builder()
            .name("Season 5")
            .seasonNumber(5)
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(80)
                        .name("Episode 80")
                        .airDate(LocalDate.of(2025, 1, 15))
                        .imageSources(List.of())
                        .build()))
            .imageSources(List.of())
            .build());

    var result = dateResolver.resolve(LIBRARY, "12345", LocalDate.of(2025, 1, 15));
    assertThat(found(result).seasonNumber()).isEqualTo(5);
    assertThat(found(result).episodeNumber()).isEqualTo(80);
  }

  @Test
  @DisplayName("Should report not found when no episode matches date")
  void shouldReportNotFoundWhenNoEpisodeMatchesDate() {
    fakeProvider.addSeasonMapping("12345", 2025, 10);
    fakeProvider.addSeasonDetails(
        "12345",
        10,
        SeasonDetails.builder()
            .name("Season 10")
            .seasonNumber(10)
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Episode 1")
                        .airDate(LocalDate.of(2025, 3, 1))
                        .imageSources(List.of())
                        .build()))
            .imageSources(List.of())
            .build());

    var result = dateResolver.resolve(LIBRARY, "12345", LocalDate.of(2025, 12, 25));

    assertThat(result).isInstanceOf(MetadataFetchOutcome.NotFound.class);
  }

  @Test
  @DisplayName("Should report not found when no season matches the year")
  void shouldReportNotFoundWhenNoSeasonMatchesTheYear() {
    var result = dateResolver.resolve(LIBRARY, "unknown", LocalDate.of(2025, 1, 1));

    assertThat(result).isInstanceOf(MetadataFetchOutcome.NotFound.class);
  }

  @Test
  @DisplayName("Should report the provider failure when season details cannot be fetched")
  void shouldReportTheProviderFailureWhenSeasonDetailsCannotBeFetched() {
    var failure = new IOException("Connection reset");
    fakeProvider.addSeasonMapping("12345", 2025, 10);
    fakeProvider.failSeasonDetails("12345", 10, failure);

    var result = dateResolver.resolve(LIBRARY, "12345", LocalDate.of(2025, 6, 15));

    assertThat(result).isEqualTo(new MetadataFetchOutcome.Failed<>(failure));
  }

  @Test
  @DisplayName("Should skip episodes when air date is null")
  void shouldSkipEpisodesWhenAirDateIsNull() {
    fakeProvider.addSeasonMapping("12345", 2025, 1);
    fakeProvider.addSeasonDetails(
        "12345",
        1,
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .episodes(
                List.of(
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(1)
                        .name("Episode 1")
                        .airDate(null)
                        .imageSources(List.of())
                        .build(),
                    SeasonDetails.EpisodeDetails.builder()
                        .episodeNumber(2)
                        .name("Episode 2")
                        .airDate(LocalDate.of(2025, 6, 15))
                        .imageSources(List.of())
                        .build()))
            .imageSources(List.of())
            .build());

    var result = dateResolver.resolve(LIBRARY, "12345", LocalDate.of(2025, 6, 15));
    assertThat(found(result).episodeNumber()).isEqualTo(2);
  }

  private static class FakeSeriesMetadataProvider implements SeriesMetadataProvider {

    private final Map<String, Map<Integer, Integer>> seasonMappings = new HashMap<>();
    private final Map<String, Map<Integer, SeasonDetails>> seasonDetailsMap = new HashMap<>();
    private final Map<String, Map<Integer, IOException>> seasonDetailsFailures = new HashMap<>();

    void addSeasonMapping(String externalId, int year, int seasonNumber) {
      seasonMappings.computeIfAbsent(externalId, k -> new HashMap<>()).put(year, seasonNumber);
    }

    void addSeasonDetails(String externalId, int seasonNumber, SeasonDetails details) {
      seasonDetailsMap.computeIfAbsent(externalId, k -> new HashMap<>()).put(seasonNumber, details);
    }

    void failSeasonDetails(String externalId, int seasonNumber, IOException failure) {
      seasonDetailsFailures
          .computeIfAbsent(externalId, k -> new HashMap<>())
          .put(seasonNumber, failure);
    }

    @Override
    public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
        UUID libraryId, String seriesExternalId, int seasonNumber) {
      var failure =
          seasonDetailsFailures.getOrDefault(seriesExternalId, Map.of()).get(seasonNumber);
      if (failure != null) {
        return new MetadataFetchOutcome.Failed<>(failure);
      }

      return Optional.ofNullable(
              seasonDetailsMap.getOrDefault(seriesExternalId, Map.of()).get(seasonNumber))
          .<MetadataFetchOutcome<SeasonDetails>>map(MetadataFetchOutcome.Found::new)
          .orElseGet(MetadataFetchOutcome.NotFound::new);
    }

    @Override
    public MetadataFetchOutcome<Integer> resolveSeasonNumber(
        UUID libraryId, String seriesExternalId, int parsedSeasonNumber) {
      var mapping = seasonMappings.getOrDefault(seriesExternalId, Map.of()).get(parsedSeasonNumber);
      if (mapping == null) {
        return new MetadataFetchOutcome.NotFound<>();
      }

      return new MetadataFetchOutcome.Found<>(mapping);
    }

    @Override
    public MetadataSearchOutcome search(VideoFileParserResult parserResult) {
      return new NotFound();
    }

    @Override
    public MetadataFetchOutcome<MetadataResult<Series>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      return new MetadataFetchOutcome.NotFound<>();
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }
}
