package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.dataloaders.SeriesDataLoader;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
      SeriesDataLoader.class,
      EpisodeFieldResolver.class,
      SeriesFieldResolver.class,
      SeriesResolver.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Season Field Resolver Tests")
class SeasonFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private UserAccountRepository userAccountRepository;
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

    String filename =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ series(id: \"%s\") { seasons { episodes { files { filename } } } } }", seriesId),
            "data.series.seasons[0].episodes[0].files[0].filename");

    assertThat(filename).isEqualTo("breaking.bad.s01e01.mkv");
  }

  @Test
  @DisplayName("Should resolve each season's own series when seasons span series")
  void shouldResolveEachSeasonsOwnSeriesWhenSeasonsSpanSeries() {
    var drama = seriesTitled("Drama");
    var comedy = seriesTitled("Comedy");
    var dramaSeason = servedSeasonOf(drama);
    var comedySeason = servedSeasonOf(comedy);
    when(seriesService.findSeriesByIds(anyCollection()))
        .thenAnswer(read -> onlyRequested(read.getArgument(0), List.of(drama, comedy)));

    Map<String, Object> data =
        dgsQueryExecutor
            .execute(
                """
                {
                  drama: season(id: "%s") { series { title } }
                  comedy: season(id: "%s") { series { title } }
                }
                """
                    .formatted(dramaSeason.getId(), comedySeason.getId()))
            .getData();

    assertThat(data)
        .isEqualTo(
            Map.of(
                "drama", Map.of("series", Map.of("title", "Drama")),
                "comedy", Map.of("series", Map.of("title", "Comedy"))));
  }

  @Test
  @DisplayName(
      "Should fail only the season whose series is missing when another season's series resolves")
  void shouldFailOnlyTheSeasonWhoseSeriesIsMissingWhenAnotherSeasonsSeriesResolves() {
    var found = seriesTitled("Drama");
    var missing = seriesTitled("Comedy");
    var foundSeason = servedSeasonOf(found);
    var orphan = servedSeasonOf(missing);
    when(seriesService.findSeriesByIds(anyCollection()))
        .thenAnswer(read -> onlyRequested(read.getArgument(0), List.of(found)));

    var result =
        dgsQueryExecutor.execute(
            """
            {
              found: season(id: "%s") { series { title } }
              orphan: season(id: "%s") { series { title } }
            }
            """
                .formatted(foundSeason.getId(), orphan.getId()));

    Map<String, Object> data = result.getData();
    assertThat(data)
        .containsEntry("found", Map.of("series", Map.of("title", "Drama")))
        .containsEntry("orphan", null);
    assertThat(result.getErrors())
        .singleElement()
        .satisfies(error -> assertThat(error.getPath()).first().isEqualTo("orphan"));
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
    when(seriesService.findSeriesByIds(Set.of(seriesId))).thenReturn(List.of());

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

  private static Series seriesTitled(String title) {
    var series = Series.builder().title(title).build();
    series.setId(UUID.randomUUID());
    return series;
  }

  private static List<Series> onlyRequested(Collection<UUID> requestedIds, List<Series> series) {
    return series.stream().filter(item -> requestedIds.contains(item.getId())).toList();
  }

  private Season servedSeasonOf(Series series) {
    var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
    season.setId(UUID.randomUUID());
    when(seriesService.findSeasonById(season.getId())).thenReturn(Optional.of(season));
    return season;
  }
}
