package com.streamarr.server.services.metadata.movie;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.fakes.FakeTmdbHttpService;
import com.streamarr.server.services.metadata.TmdbSearchDelegate;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResult;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResults;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("TMDB Movie Provider Tests")
class TMDBMovieProviderTest {

  private final FakeTmdbHttpService fakeTmdbHttpService = new FakeTmdbHttpService();

  private final TmdbSearchDelegate searchDelegate = new TmdbSearchDelegate(fakeTmdbHttpService);

  private final TMDBMovieProvider provider =
      new TMDBMovieProvider(fakeTmdbHttpService, searchDelegate);

  @Test
  @DisplayName("Should find movie when year-filtered search returns no results")
  void shouldFindMovieWhenYearFilteredSearchReturnsNoResults() {
    var videoInfo = VideoFileParserResult.builder().title("Arrival").year("2015").build();

    var arrivalResult =
        TmdbSearchResult.builder()
            .id(329865)
            .title("Arrival")
            .originalTitle("Arrival")
            .releaseDate("2016-11-11")
            .popularity(45.0)
            .build();

    fakeTmdbHttpService.setMovieSearchResponse(
        "2015", TmdbSearchResults.builder().results(Collections.emptyList()).build());
    fakeTmdbHttpService.setMovieSearchResponse(
        null, TmdbSearchResults.builder().results(List.of(arrivalResult)).build());

    var result = provider.search(videoInfo);

    assertThat(result).isPresent();
    assertThat(result.get().externalId()).isEqualTo("329865");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }
}
