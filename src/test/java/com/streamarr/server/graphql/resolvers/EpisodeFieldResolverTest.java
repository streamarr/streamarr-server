package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.dataloaders.SeasonDataLoader;
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
      EpisodeFieldResolver.class,
      SeasonDataLoader.class,
      SeriesFieldResolver.class,
      SeriesResolver.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class
    })
@DisplayName("Episode Field Resolver Tests")
class EpisodeFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private UserAccountRepository userAccountRepository;
  @MockitoBean private SeriesService seriesService;

  @Test
  @DisplayName("Should resolve each episode's own season when episodes span seasons")
  void shouldResolveEachEpisodesOwnSeasonWhenEpisodesSpanSeasons() {
    var first = seasonNumbered(1);
    var second = seasonNumbered(2);
    var pilot = servedEpisodeOf(first);
    var finale = servedEpisodeOf(second);
    when(seriesService.findSeasonsByIds(anyCollection()))
        .thenAnswer(read -> onlyRequested(read.getArgument(0), List.of(first, second)));

    Map<String, Object> data =
        dgsQueryExecutor
            .execute(
                """
                {
                  pilot: episode(id: "%s") { season { seasonNumber } }
                  finale: episode(id: "%s") { season { seasonNumber } }
                }
                """
                    .formatted(pilot.getId(), finale.getId()))
            .getData();

    assertThat(data)
        .isEqualTo(
            Map.of(
                "pilot", Map.of("season", Map.of("seasonNumber", 1)),
                "finale", Map.of("season", Map.of("seasonNumber", 2))));
  }

  @Test
  @DisplayName(
      "Should fail only the episode whose season is missing when another episode's season resolves")
  void shouldFailOnlyTheEpisodeWhoseSeasonIsMissingWhenAnotherEpisodesSeasonResolves() {
    var found = seasonNumbered(1);
    var missing = seasonNumbered(2);
    var pilot = servedEpisodeOf(found);
    var orphan = servedEpisodeOf(missing);
    when(seriesService.findSeasonsByIds(anyCollection()))
        .thenAnswer(read -> onlyRequested(read.getArgument(0), List.of(found)));

    var result =
        dgsQueryExecutor.execute(
            """
            {
              pilot: episode(id: "%s") { season { seasonNumber } }
              orphan: episode(id: "%s") { season { seasonNumber } }
            }
            """
                .formatted(pilot.getId(), orphan.getId()));

    Map<String, Object> data = result.getData();
    assertThat(data)
        .containsEntry("pilot", Map.of("season", Map.of("seasonNumber", 1)))
        .containsEntry("orphan", null);
    assertThat(result.getErrors())
        .singleElement()
        .satisfies(error -> assertThat(error.getPath()).first().isEqualTo("orphan"));
  }

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
    when(seriesService.findSeasonsByIds(Set.of(seasonId))).thenReturn(List.of());

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

  private static Season seasonNumbered(int seasonNumber) {
    var season =
        Season.builder().title("Season " + seasonNumber).seasonNumber(seasonNumber).build();
    season.setId(UUID.randomUUID());
    return season;
  }

  private static List<Season> onlyRequested(Collection<UUID> requestedIds, List<Season> seasons) {
    return seasons.stream().filter(season -> requestedIds.contains(season.getId())).toList();
  }

  private Episode servedEpisodeOf(Season season) {
    var episode = Episode.builder().title("Episode").episodeNumber(1).season(season).build();
    episode.setId(UUID.randomUUID());
    when(seriesService.findEpisodeById(episode.getId())).thenReturn(Optional.of(episode));
    return episode;
  }
}
