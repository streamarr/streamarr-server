package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.services.SeriesService;
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
    classes = {
      SeasonFieldResolver.class,
      EpisodeFieldResolver.class,
      SeriesFieldResolver.class,
      SeriesResolver.class
    })
@DisplayName("Episode Field Resolver Tests")
class EpisodeFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private SeriesService seriesService;

  @Test
  @DisplayName("Should return season not found error when episode references missing season")
  void shouldReturnSeasonNotFoundErrorWhenEpisodeReferencesMissingSeason() {
    var seasonId = UUID.randomUUID();
    var season = Season.builder().title("Season 1").seasonNumber(1).build();
    season.setId(seasonId);

    var episodeId = UUID.randomUUID();
    var episode = Episode.builder().title("Pilot").episodeNumber(1).season(season).build();
    episode.setId(episodeId);

    when(seriesService.findEpisodeById(episodeId)).thenReturn(Optional.of(episode));
    when(seriesService.findSeasonById(seasonId)).thenReturn(Optional.empty());

    var result =
        dgsQueryExecutor.execute(
            String.format("{ episode(id: \"%s\") { season { seasonNumber } } }", episodeId));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.getMessage())
                    .contains("Season not found: " + seasonId)
                    .contains(episodeId.toString()));
  }
}
