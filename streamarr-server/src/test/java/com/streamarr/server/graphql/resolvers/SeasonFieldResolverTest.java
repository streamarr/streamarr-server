package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithProfileContext
@SpringBootTest(
    classes = {
      SeasonFieldResolver.class,
      EpisodeFieldResolver.class,
      SeriesFieldResolver.class,
      SeriesResolver.class,
      SecurityContextAuthorizationService.class
    })
@DisplayName("Season Field Resolver Tests")
class SeasonFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ProfileRepository profileRepository;

  @MockitoBean private AccountProfileRepository accountProfileRepository;

  @MockitoBean private SeriesService seriesService;

  @Test
  @DisplayName("Should return episodes when season queried with episodes field")
  void shouldReturnEpisodesWhenSeasonQueriedWithEpisodesField() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);

    var seasonId = UUID.randomUUID();
    var season = Season.builder().title("Season 1").seasonNumber(1).build();
    season.setId(seasonId);

    var episode = Episode.builder().title("Pilot").episodeNumber(1).build();
    episode.setId(UUID.randomUUID());

    when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
    when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
    when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ series(id: \"%s\") { seasons { episodes { title episodeNumber } } } }",
                seriesId),
            "data.series.seasons[0].episodes[0].title");

    assertThat(title).isEqualTo("Pilot");
  }

  @Test
  @DisplayName("Should return files for episodes")
  void shouldReturnFilesForEpisodes() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);

    var seasonId = UUID.randomUUID();
    var season = Season.builder().title("Season 1").seasonNumber(1).build();
    season.setId(seasonId);

    var episodeId = UUID.randomUUID();
    var episode = Episode.builder().title("Pilot").episodeNumber(1).build();
    episode.setId(episodeId);

    var mediaFile =
        MediaFile.builder()
            .filename("breaking.bad.s01e01.mkv")
            .filepathUri("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv")
            .size(1500000000L)
            .build();
    mediaFile.setId(UUID.randomUUID());

    when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
    when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
    when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));
    when(seriesService.findMediaFiles(episodeId)).thenReturn(List.of(mediaFile));

    String filepathUri =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ series(id: \"%s\") { seasons { episodes { files { filepathUri } } } } }",
                seriesId),
            "data.series.seasons[0].episodes[0].files[0].filepathUri");

    assertThat(filepathUri).isEqualTo("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv");
  }

  @Test
  @DisplayName("Should return series not found error when season references missing series")
  void shouldReturnSeriesNotFoundErrorWhenSeasonReferencesMissingSeries() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);

    var seasonId = UUID.randomUUID();
    var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
    season.setId(seasonId);

    when(seriesService.findSeasonById(seasonId)).thenReturn(Optional.of(season));
    when(seriesService.findById(seriesId)).thenReturn(Optional.empty());

    var result =
        dgsQueryExecutor.execute(
            String.format("{ season(id: \"%s\") { series { title } } }", seasonId));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getMessage())
                    .contains("Series not found: " + seriesId)
                    .contains(seasonId.toString()));
  }
}
