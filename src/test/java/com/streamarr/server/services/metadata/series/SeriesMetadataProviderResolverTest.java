package com.streamarr.server.services.metadata.series;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Series Metadata Provider Resolver Tests")
class SeriesMetadataProviderResolverTest {

  @Test
  @DisplayName("Should return search result when provider matches library strategy")
  void shouldReturnSearchResultWhenProviderMatchesLibraryStrategy() {
    var expectedResult =
        RemoteSearchResult.builder()
            .title("Breaking Bad")
            .externalId("1396")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(
                new FakeSeriesMetadataProvider(
                    expectedResult, null, List.of(), Optional.empty())));

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var parserResult = VideoFileParserResult.builder().title("Breaking Bad").build();

    var result = resolver.search(library, parserResult);

    assertThat(result).isPresent();
    assertThat(result.get().title()).isEqualTo("Breaking Bad");
    assertThat(result.get().externalId()).isEqualTo("1396");
  }

  @Test
  @DisplayName("Should return empty when no provider matches library strategy for search")
  void shouldReturnEmptyWhenNoProviderMatchesLibraryStrategyForSearch() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var parserResult = VideoFileParserResult.builder().title("Breaking Bad").build();

    var result = resolver.search(library, parserResult);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return series when provider matches library strategy")
  void shouldReturnSeriesWhenProviderMatchesLibraryStrategy() {
    var expectedSeries = Series.builder().title("Breaking Bad").build();

    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(
                new FakeSeriesMetadataProvider(
                    null, expectedSeries, List.of(), Optional.empty())));

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Breaking Bad")
            .externalId("1396")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

    var result = resolver.getMetadata(searchResult, library);

    assertThat(result).isPresent();
    assertThat(result.get().entity().getTitle()).isEqualTo("Breaking Bad");
  }

  @Test
  @DisplayName("Should return season numbers when provider matches library strategy")
  void shouldReturnSeasonNumbersWhenProviderMatchesLibraryStrategy() {
    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(
                new FakeSeriesMetadataProvider(
                    null, null, List.of(1, 2, 3), Optional.empty())));

    var library =
        Library.builder()
            .id(UUID.randomUUID())
            .name("TV")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();

    var result = resolver.getAvailableSeasonNumbers(library, "1396");

    assertThat(result).containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName(
      "Should return empty list when no provider matches library strategy for season numbers")
  void shouldReturnEmptyListWhenNoProviderMatchesForSeasonNumbers() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();

    var result = resolver.getAvailableSeasonNumbers(library, "1396");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return empty when no provider matches library strategy for metadata")
  void shouldReturnEmptyWhenNoProviderMatchesLibraryStrategyForMetadata() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Breaking Bad")
            .externalId("1396")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

    var result = resolver.getMetadata(searchResult, library);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("Should return season details when provider matches library strategy")
  void shouldReturnSeasonDetailsWhenProviderMatchesLibraryStrategy() {
    var expectedDetails =
        SeasonDetails.builder()
            .name("Season 1")
            .seasonNumber(1)
            .overview("The first season")
            .imageSources(List.of())
            .episodes(List.of())
            .build();

    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(
                new FakeSeriesMetadataProvider(
                    null, null, List.of(), Optional.of(expectedDetails))));

    var library =
        Library.builder()
            .id(UUID.randomUUID())
            .name("TV")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();

    var result = resolver.getSeasonDetails(library, "1396", 1);

    assertThat(result).isPresent();
    assertThat(result.get().name()).isEqualTo("Season 1");
    assertThat(result.get().seasonNumber()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should return empty when no provider matches library strategy for season details")
  void shouldReturnEmptyWhenNoProviderMatchesForSeasonDetails() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();

    var result = resolver.getSeasonDetails(library, "1396", 1);

    assertThat(result).isEmpty();
  }

  private static class FakeSeriesMetadataProvider implements SeriesMetadataProvider {

    private final RemoteSearchResult searchResult;
    private final Series series;
    private final List<Integer> seasonNumbers;
    private final Optional<SeasonDetails> seasonDetails;

    FakeSeriesMetadataProvider(
        RemoteSearchResult searchResult,
        Series series,
        List<Integer> seasonNumbers,
        Optional<SeasonDetails> seasonDetails) {
      this.searchResult = searchResult;
      this.series = series;
      this.seasonNumbers = seasonNumbers;
      this.seasonDetails = seasonDetails;
    }

    @Override
    public Optional<RemoteSearchResult> search(VideoFileParserResult parserResult) {
      return Optional.ofNullable(searchResult);
    }

    @Override
    public Optional<MetadataResult<Series>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      if (series == null) {
        return Optional.empty();
      }
      return Optional.of(new MetadataResult<>(series, List.of(), Map.of(), Map.of()));
    }

    @Override
    public Optional<SeasonDetails> getSeasonDetails(
        UUID libraryId, String seriesExternalId, int seasonNumber) {
      return seasonDetails;
    }

    @Override
    public List<Integer> getAvailableSeasonNumbers(UUID libraryId, String seriesExternalId) {
      return seasonNumbers;
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }
}
