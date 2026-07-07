package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.jooq.generated.Tables;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@EnableDgsTest
@DisplayName("Continue Watching Resolver Integration Tests")
class ContinueWatchingResolverIT extends AbstractIntegrationTest {

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private DSLContext dsl;

  @BeforeEach
  void setUp() {
    dsl.deleteFrom(Tables.SESSION_PROGRESS)
        .where(Tables.SESSION_PROGRESS.USER_ID.eq(USER_ID))
        .execute();
    dsl.deleteFrom(Tables.WATCH_HISTORY).where(Tables.WATCH_HISTORY.USER_ID.eq(USER_ID)).execute();
  }

  @Test
  @DisplayName("Should resolve episode season details when continue watching returns an episode")
  @SuppressWarnings("unchecked")
  void shouldResolveEpisodeSeasonDetailsWhenContinueWatchingReturnsAnEpisode() {
    var episode = createEpisodeWithProgress();

    var result =
        dgsQueryExecutor.execute(
            """
            {
              continueWatching(first: 1) {
                ... on Episode {
                  title
                  season {
                    seasonNumber
                    series {
                      title
                    }
                  }
                }
              }
            }
            """);

    assertThat(result.getErrors()).isEmpty();

    Map<String, Object> data = result.getData();
    var items = (List<Map<String, Object>>) data.get("continueWatching");
    var item = items.getFirst();
    var season = (Map<String, Object>) item.get("season");
    var series = (Map<String, Object>) season.get("series");

    assertThat(item).containsEntry("title", episode.getTitle());
    assertThat(season).containsEntry("seasonNumber", 1);
    assertThat(series).containsEntry("title", "Test Series");
  }

  private Episode createEpisodeWithProgress() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series = createSeries(library);
    var season = createSeason(library, series);

    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("pilot.mkv")
            .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
            .build();

    var episode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .episodeNumber(1)
                .title("Pilot")
                .season(season)
                .library(library)
                .files(Set.of(file))
                .build());

    sessionProgressRepository.saveAndFlush(
        SessionProgress.builder()
            .sessionId(UUID.randomUUID())
            .userId(USER_ID)
            .mediaFileId(episode.getFiles().iterator().next().getId())
            .positionSeconds(900)
            .percentComplete(25.0)
            .durationSeconds(3600)
            .build());

    return episode;
  }

  private Series createSeries(Library library) {
    return seriesRepository.saveAndFlush(
        Series.builder().title("Test Series").titleSort("Test Series").library(library).build());
  }

  private Season createSeason(Library library, Series series) {
    return seasonRepository.saveAndFlush(
        Season.builder().title("Season 1").seasonNumber(1).series(series).library(library).build());
  }
}
