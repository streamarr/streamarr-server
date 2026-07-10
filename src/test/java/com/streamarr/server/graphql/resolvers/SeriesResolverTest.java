package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Map;
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
@SpringBootTest(classes = {SeriesResolver.class, SecurityContextAuthorizationService.class})
@DisplayName("Series Resolver Tests")
class SeriesResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ProfileRepository profileRepository;

  @MockitoBean private AccountProfileRepository accountProfileRepository;

  @MockitoBean private SeriesService seriesService;

  @Test
  @DisplayName("Should return series when valid ID provided")
  void shouldReturnSeriesWhenValidIdProvided() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").tagline("All Hail the King").build();
    series.setId(seriesId);

    when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { title tagline } }", seriesId),
            "data.series.title");

    assertThat(title).isEqualTo("Breaking Bad");
  }

  @Test
  @DisplayName("Should return background-created series when creator absent")
  void shouldReturnBackgroundCreatedSeriesWhenCreatorAbsent() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Background Series").build();
    series.setId(seriesId);

    when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));

    var result =
        dgsQueryExecutor.execute(
            String.format("{ series(id: \"%s\") { title createdBy } }", seriesId));

    assertThat(result.getErrors()).isEmpty();
    var data = result.<Map<String, Object>>getData();
    var seriesData = (Map<?, ?>) data.get("series");
    assertThat(seriesData.get("title")).isEqualTo("Background Series");
    assertThat(seriesData.containsKey("createdBy")).isTrue();
    assertThat(seriesData.get("createdBy")).isNull();
  }

  @Test
  @DisplayName("Should return null when series not found")
  void shouldReturnNullWhenSeriesNotFound() {
    when(seriesService.findById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { title } }", UUID.randomUUID()), "data.series");

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ series(id: \"not-a-uuid\") { title } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return files for series")
  void shouldReturnFilesForSeries() {
    var seriesId = UUID.randomUUID();
    var series = Series.builder().title("Breaking Bad").build();
    series.setId(seriesId);

    var mediaFile =
        MediaFile.builder()
            .filename("breaking.bad.s01e01.mkv")
            .filepathUri("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv")
            .size(1500000000L)
            .build();
    mediaFile.setId(UUID.randomUUID());

    when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
    when(seriesService.findMediaFiles(seriesId)).thenReturn(List.of(mediaFile));

    String filepathUri =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ series(id: \"%s\") { files { filepathUri } } }", seriesId),
            "data.series.files[0].filepathUri");

    assertThat(filepathUri).isEqualTo("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv");
  }

  @Test
  @DisplayName("Should return season when valid ID provided")
  void shouldReturnSeasonWhenValidIdProvided() {
    var seasonId = UUID.randomUUID();
    var season = Season.builder().title("Season 1").seasonNumber(1).build();
    season.setId(seasonId);

    when(seriesService.findSeasonById(seasonId)).thenReturn(Optional.of(season));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ season(id: \"%s\") { title seasonNumber } }", seasonId),
            "data.season.title");

    assertThat(title).isEqualTo("Season 1");
  }

  @Test
  @DisplayName("Should return null when season not found")
  void shouldReturnNullWhenSeasonNotFound() {
    when(seriesService.findSeasonById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ season(id: \"%s\") { title } }", UUID.randomUUID()), "data.season");

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Should return error when invalid season ID provided")
  void shouldReturnErrorWhenInvalidSeasonIdProvided() {
    var result = dgsQueryExecutor.execute("{ season(id: \"not-a-uuid\") { title } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return episode when valid ID provided")
  void shouldReturnEpisodeWhenValidIdProvided() {
    var episodeId = UUID.randomUUID();
    var episode = Episode.builder().title("Pilot").episodeNumber(1).build();
    episode.setId(episodeId);

    when(seriesService.findEpisodeById(episodeId)).thenReturn(Optional.of(episode));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ episode(id: \"%s\") { title episodeNumber } }", episodeId),
            "data.episode.title");

    assertThat(title).isEqualTo("Pilot");
  }

  @Test
  @DisplayName("Should return null when episode not found")
  void shouldReturnNullWhenEpisodeNotFound() {
    when(seriesService.findEpisodeById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ episode(id: \"%s\") { title } }", UUID.randomUUID()), "data.episode");

    assertThat(result).isNull();
  }
}
