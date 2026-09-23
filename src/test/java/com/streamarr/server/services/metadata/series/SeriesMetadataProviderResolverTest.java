package com.streamarr.server.services.metadata.series;

import static com.streamarr.server.fixtures.MetadataFixture.found;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.MetadataSearchOutcome;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.Found;
import com.streamarr.server.services.metadata.MetadataSearchOutcome.TemporarilyUnavailable;
import com.streamarr.server.services.metadata.RemoteSearchResult;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Series Metadata Provider Resolver Tests")
class SeriesMetadataProviderResolverTest {

  @Test
  @DisplayName("Should return unavailable when no provider matches library strategy for search")
  void shouldReturnUnavailableWhenNoProviderMatchesLibraryStrategyForSearch() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var parserResult = VideoFileParserResult.builder().title("Breaking Bad").build();

    var result = resolver.search(library, parserResult);

    assertThat(result).isInstanceOf(TemporarilyUnavailable.class);
  }

  @Test
  @DisplayName("Should return series when provider matches library strategy")
  void shouldReturnSeriesWhenProviderMatchesLibraryStrategy() {
    var expectedSeries = Series.builder().title("Breaking Bad").build();

    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(fakeProviderBuilder().series(expectedSeries).build()));

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();
    var searchResult =
        RemoteSearchResult.builder()
            .title("Breaking Bad")
            .externalId("1396")
            .externalSourceType(ExternalSourceType.TMDB)
            .build();

    var result = resolver.getMetadata(searchResult, library);
    assertThat(found(result).entity().getTitle()).isEqualTo("Breaking Bad");
  }

  @Test
  @DisplayName("Should return season numbers when provider matches library strategy")
  void shouldReturnSeasonNumbersWhenProviderMatchesLibraryStrategy() {
    var resolver =
        new SeriesMetadataProviderResolver(
            List.of(fakeProviderBuilder().seasonNumbers(List.of(1, 2, 3)).build()));

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
  @DisplayName("Should report failed fetch when no provider matches library strategy for metadata")
  void shouldReportFailedFetchWhenNoProviderMatchesLibraryStrategyForMetadata() {
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

    assertThat(result)
        .isInstanceOfSatisfying(
            MetadataFetchOutcome.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ItemFailureReason.MISCONFIGURED));
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
                fakeProviderBuilder()
                    .seasonDetails(new MetadataFetchOutcome.Found<>(expectedDetails))
                    .build()));

    var library =
        Library.builder()
            .id(UUID.randomUUID())
            .name("TV")
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();

    var result = resolver.getSeasonDetails(library, "1396", 1);
    assertThat(found(result).name()).isEqualTo("Season 1");
    assertThat(found(result).seasonNumber()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should report failed fetch when no provider matches library strategy for season details")
  void shouldReportFailedFetchWhenNoProviderMatchesForSeasonDetails() {
    var resolver = new SeriesMetadataProviderResolver(List.of());

    var library =
        Library.builder().name("TV").externalAgentStrategy(ExternalAgentStrategy.TMDB).build();

    var result = resolver.getSeasonDetails(library, "1396", 1);

    assertThat(result).isInstanceOf(MetadataFetchOutcome.Failed.class);
  }

  private static class FakeSeriesMetadataProvider implements SeriesMetadataProvider {

    private final RemoteSearchResult searchResult;
    private final Series series;
    private final List<Integer> seasonNumbers;
    private final MetadataFetchOutcome<SeasonDetails> seasonDetails;

    @Builder
    private FakeSeriesMetadataProvider(
        RemoteSearchResult searchResult,
        Series series,
        List<Integer> seasonNumbers,
        MetadataFetchOutcome<SeasonDetails> seasonDetails) {
      this.searchResult = searchResult;
      this.series = series;
      this.seasonNumbers = seasonNumbers;
      this.seasonDetails = seasonDetails;
    }

    @Override
    public MetadataSearchOutcome search(VideoFileParserResult parserResult) {
      return Optional.ofNullable(searchResult)
          .<MetadataSearchOutcome>map(Found::new)
          .orElseGet(
              () ->
                  new TemporarilyUnavailable(
                      new IllegalStateException("No fake search result configured")));
    }

    @Override
    public MetadataFetchOutcome<MetadataResult<Series>> getMetadata(
        RemoteSearchResult remoteSearchResult, Library library) {
      if (series == null) {
        return new MetadataFetchOutcome.Failed<>(
            new IllegalStateException("No fake series configured"));
      }
      return new MetadataFetchOutcome.Found<>(
          new MetadataResult<>(series, List.of(), Map.of(), Map.of()));
    }

    @Override
    public MetadataFetchOutcome<SeasonDetails> getSeasonDetails(
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

  private static FakeSeriesMetadataProvider.FakeSeriesMetadataProviderBuilder
      fakeProviderBuilder() {
    return FakeSeriesMetadataProvider.builder()
        .seasonNumbers(List.of())
        .seasonDetails(new MetadataFetchOutcome.NotFound<>());
  }
}
