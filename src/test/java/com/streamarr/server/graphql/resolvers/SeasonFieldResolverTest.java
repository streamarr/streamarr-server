package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
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
@SpringBootTest(
    classes = {SeasonFieldResolver.class, SeriesFieldResolver.class, SeriesResolver.class})
@DisplayName("Season Field Resolver Tests")
class SeasonFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private SeriesRepository seriesRepository;
  @MockitoBean private MediaFileRepository mediaFileRepository;
  @MockitoBean private CompanyRepository companyRepository;
  @MockitoBean private PersonRepository personRepository;
  @MockitoBean private GenreRepository genreRepository;
  @MockitoBean private SeasonRepository seasonRepository;
  @MockitoBean private EpisodeRepository episodeRepository;

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

    when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
    when(seasonRepository.findBySeriesId(seriesId)).thenReturn(List.of(season));
    when(episodeRepository.findBySeasonId(seasonId)).thenReturn(List.of(episode));

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

    when(seriesRepository.findById(seriesId)).thenReturn(Optional.of(series));
    when(seasonRepository.findBySeriesId(seriesId)).thenReturn(List.of(season));
    when(episodeRepository.findBySeasonId(seasonId)).thenReturn(List.of(episode));
    when(mediaFileRepository.findByMediaId(episodeId)).thenReturn(List.of(mediaFile));

    String filepathUri =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ series(id: \"%s\") { seasons { episodes { files { filepathUri } } } } }",
                seriesId),
            "data.series.seasons[0].episodes[0].files[0].filepathUri");

    assertThat(filepathUri)
        .isEqualTo("/media/shows/Breaking Bad/Season 1/breaking.bad.s01e01.mkv");
  }
}
