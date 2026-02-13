package com.streamarr.server.services.metadata.movie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.metadata.TheMovieDatabaseHttpService;
import com.streamarr.server.services.metadata.TmdbSearchDelegate;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResult;
import com.streamarr.server.services.metadata.tmdb.TmdbSearchResults;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@Tag("UnitTest")
@ExtendWith(MockitoExtension.class)
@DisplayName("TMDB Movie Provider Tests")
class TMDBMovieProviderTest {

  private final TheMovieDatabaseHttpService theMovieDatabaseHttpService =
      mock(TheMovieDatabaseHttpService.class);

  private final TmdbSearchDelegate searchDelegate =
      new TmdbSearchDelegate(theMovieDatabaseHttpService);

  private final TMDBMovieProvider provider =
      new TMDBMovieProvider(theMovieDatabaseHttpService, searchDelegate);

  @Test
  @DisplayName("Should find movie when year-filtered search returns no results")
  void shouldFindMovieWhenYearFilteredSearchReturnsNoResults()
      throws IOException, InterruptedException {
    var videoInfo = VideoFileParserResult.builder().title("Arrival").year("2015").build();

    var emptyResults = TmdbSearchResults.builder().results(Collections.emptyList()).build();

    var arrivalResult =
        TmdbSearchResult.builder()
            .id(329865)
            .title("Arrival")
            .originalTitle("Arrival")
            .releaseDate("2016-11-11")
            .popularity(45.0)
            .build();
    var resultsWithArrival = TmdbSearchResults.builder().results(List.of(arrivalResult)).build();

    when(theMovieDatabaseHttpService.searchForMovie(
            argThat(arg -> arg != null && "2015".equals(arg.year()))))
        .thenReturn(emptyResults);
    when(theMovieDatabaseHttpService.searchForMovie(
            argThat(arg -> arg != null && arg.year() == null)))
        .thenReturn(resultsWithArrival);

    var result = provider.search(videoInfo);

    assertThat(result).isPresent();
    assertThat(result.get().externalId()).isEqualTo("329865");
    assertThat(result.get().externalSourceType()).isEqualTo(ExternalSourceType.TMDB);
  }
}
