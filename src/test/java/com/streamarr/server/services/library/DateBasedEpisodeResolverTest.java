package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.metadata.series.SeasonDetails;
import com.streamarr.server.services.metadata.series.SeriesMetadataProvider;
import com.streamarr.server.services.metadata.series.SeriesMetadataProviderResolver;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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

    assertThat(result).isPresent();
    assertThat(result.get().seasonNumber()).isEqualTo(10);
    assertThat(result.get().episodeNumber()).isEqualTo(42);
  }

  @Test
  @DisplayName("Should fall back to year minus one for broadcast-year crossover")
  void shouldFallbackToYearMinusOneForBroadcastYearCrossover() {
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

    assertThat(result).isPresent();
    assertThat(result.get().seasonNumber()).isEqualTo(5);
    assertThat(result.get().episodeNumber()).isEqualTo(80);
  }

  @Test
  @DisplayName("Should return empty when no episode matches date")
  void shouldReturnEmptyWhenNoEpisodeMatchesDate() {
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

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when season resolution fails")
  void shouldReturnEmptyWhenSeasonResolutionFails() {
    var result = dateResolver.resolve(LIBRARY, "unknown", LocalDate.of(2025, 1, 1));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should skip episodes with null air date")
  void shouldSkipEpisodesWithNullAirDate() {
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

    assertThat(result).isPresent();
    assertThat(result.get().episodeNumber()).isEqualTo(2);
  }

  private static class FakeSeriesMetadataProvider implements SeriesMetadataProvider {

    private final Map<String, Map<Integer, Integer>> seasonMappings = new HashMap<>();
    private final Map<String, Map<Integer, SeasonDetails>> seasonDetailsMap = new HashMap<>();

    void addSeasonMapping(String externalId, int year, int seasonNumber) {
      seasonMappings.computeIfAbsent(externalId, k -> new HashMap<>()).put(year, seasonNumber);
    }

    void addSeasonDetails(String externalId, int seasonNumber, SeasonDetails details) {
      seasonDetailsMap.computeIfAbsent(externalId, k -> new HashMap<>()).put(seasonNumber, details);
    }

    @Override
    public Optional<SeasonDetails> getSeasonDetails(String seriesExternalId, int seasonNumber) {
      return Optional.ofNullable(
          seasonDetailsMap.getOrDefault(seriesExternalId, Map.of()).get(seasonNumber));
    }

    @Override
    public OptionalInt resolveSeasonNumber(String seriesExternalId, int parsedSeasonNumber) {
      var mapping = seasonMappings.getOrDefault(seriesExternalId, Map.of()).get(parsedSeasonNumber);
      return mapping != null ? OptionalInt.of(mapping) : OptionalInt.empty();
    }

    @Override
    public Optional<RemoteSearchResult> search(VideoFileParserResult parserResult) {
      return Optional.empty();
    }

    @Override
    public Optional<MetadataResult<Series>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      return Optional.empty();
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }
}
