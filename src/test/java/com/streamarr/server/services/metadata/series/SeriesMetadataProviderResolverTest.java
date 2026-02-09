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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
            List.of(new FakeSeriesMetadataProvider(expectedResult, null)));

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
            List.of(new FakeSeriesMetadataProvider(null, expectedSeries)));

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

  private static class FakeSeriesMetadataProvider implements SeriesMetadataProvider {

    private final RemoteSearchResult searchResult;
    private final Series series;

    FakeSeriesMetadataProvider(RemoteSearchResult searchResult, Series series) {
      this.searchResult = searchResult;
      this.series = series;
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
    public Optional<SeasonDetails> getSeasonDetails(String seriesExternalId, int seasonNumber) {
      return Optional.empty();
    }

    @Override
    public ExternalAgentStrategy getAgentStrategy() {
      return ExternalAgentStrategy.TMDB;
    }
  }
}
