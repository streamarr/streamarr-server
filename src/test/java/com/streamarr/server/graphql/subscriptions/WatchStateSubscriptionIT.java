package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;

/**
 * Every open client of a Profile receives the Profile's committed watch-state changes, resolved
 * with the same fields a query returns, and no client of another Profile receives them.
 */
@Tag("IntegrationTest")
@DisplayName("Watch State Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WatchStateSubscriptionIT extends AbstractIntegrationTest {

  private static final String FIELD = "watchStateChanged";
  private static final String OPERATION = "watch-state";
  private static final String SUBSCRIPTION =
      """
      subscription {
        watchStateChanged {
          items {
            __typename
            ... on Movie {
              id
              watchStatus
              lastWatchedAt
              watchProgress { positionSeconds }
              library { id }
              unmatched
            }
            ... on Episode { id watchStatus lastWatchedAt watchProgress { positionSeconds } }
            ... on Season { id watchStatus watchProgress { positionSeconds } }
            ... on Series {
              id
              watchStatus
              lastWatchedAt
              watchProgress { positionSeconds }
              library { id }
              unmatched
            }
          }
        }
      }
      """;
  private static final int PAUSED_POSITION_SECONDS = 900;
  private static final int LATER_POSITION_SECONDS = 1_200;
  private static final int FINISHED_POSITION_SECONDS = 7_000;

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private RuntimeStreamSessionRegistry runtimeRegistry;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private final List<UUID> streamSessionIds = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private GraphQlHttpTestClient http;

  @BeforeEach
  void openHttpClient() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
  }

  @AfterEach
  void releaseResources() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    http.close();
    activeSubscriptions.awaitNoneActive();
    streamSessionIds.forEach(runtimeRegistry::removeById);
    identities.forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver the episode, its season and its series to the Profile's other client and"
          + " nothing to another Profile when the Profile marks the episode watched")
  void shouldDeliverEpisodeSeasonAndSeriesOnlyToProfilesClientWhenProfileMarksEpisodeWatched() {
    var identity = createIdentity();
    var otherIdentity = createIdentity();
    var episode = createEpisode();
    var sentinel = createMovie();
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var otherSubscriber = subscribedSocket(authTestSupport.profileBearer(otherIdentity));

    markWatched(authTestSupport.profileBearer(identity), episode.getId());
    markWatched(authTestSupport.profileBearer(otherIdentity), sentinel.getId());

    assertThat(itemsById(nextChange(otherSubscriber)))
        .containsOnlyKeys(sentinel.getId().toString());
    var items = itemsById(nextChange(subscriber));
    assertThat(items).hasSize(3);
    var episodeItem = items.get(episode.getId().toString());
    assertThat(episodeItem.path("__typename").asString()).isEqualTo("Episode");
    assertThat(episodeItem.path("watchStatus").asString()).isEqualTo("WATCHED");
    assertThat(episodeItem.path("lastWatchedAt").isNull()).isFalse();
    var seasonItem = items.get(episode.getSeason().getId().toString());
    assertThat(seasonItem.path("__typename").asString()).isEqualTo("Season");
    assertThat(seasonItem.path("watchStatus").asString()).isEqualTo("WATCHED");
    var seriesItem = items.get(episode.getSeason().getSeries().getId().toString());
    assertThat(seriesItem.path("__typename").asString()).isEqualTo("Series");
    assertThat(seriesItem.path("watchStatus").asString()).isEqualTo("WATCHED");
    assertThat(seriesItem.path("lastWatchedAt").asString())
        .isEqualTo(episodeItem.path("lastWatchedAt").asString());
    assertThat(seriesItem.path("library").path("id").asString())
        .isEqualTo(episode.getLibrary().getId().toString());
    assertThat(seriesItem.path("unmatched").asBoolean()).isTrue();
  }

  @Test
  @DisplayName(
      "Should deliver the subscribing Profile's resume position of the episode, its season and its"
          + " series when another Profile has watched the episode")
  void shouldDeliverSubscribingProfilesResumePositionWhenAnotherProfileHasWatchedEpisode() {
    var identity = createIdentity();
    var episode = createEpisode();
    markWatched(authTestSupport.profileBearer(createIdentity()), episode.getId());
    var session = openStreamSession(identity, episode);
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));

    reportPaused(authTestSupport.profileBearer(identity), session, PAUSED_POSITION_SECONDS);

    var items = itemsById(nextChange(subscriber));
    assertThat(items.values())
        .hasSize(3)
        .allSatisfy(
            item -> {
              assertThat(item.path("watchStatus").asString()).isEqualTo("IN_PROGRESS");
              assertThat(item.path("watchProgress").path("positionSeconds").asInt())
                  .isEqualTo(PAUSED_POSITION_SECONDS);
            });
    assertThat(items.get(episode.getId().toString()).path("lastWatchedAt").isNull()).isFalse();
  }

  @Test
  @DisplayName("Should deliver the later position when two progress reports commit in turn")
  void shouldDeliverLaterPositionWhenTwoProgressReportsCommitInTurn() {
    var identity = createIdentity();
    var episode = createEpisode();
    var session = openStreamSession(identity, episode);
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var reporter = authTestSupport.profileBearer(identity);

    reportPaused(reporter, session, PAUSED_POSITION_SECONDS);
    var first = itemsById(nextChange(subscriber));
    reportPaused(reporter, session, LATER_POSITION_SECONDS);
    var second = itemsById(nextChange(subscriber));

    assertThat(positionOf(first, episode.getId())).isEqualTo(PAUSED_POSITION_SECONDS);
    assertThat(positionOf(second, episode.getId())).isEqualTo(LATER_POSITION_SECONDS);
    assertThat(positionOf(second, episode.getSeason().getSeries().getId()))
        .isEqualTo(LATER_POSITION_SECONDS);
  }

  @Test
  @DisplayName(
      "Should deliver the later position last when a second progress report commits before the"
          + " first change arrives")
  void shouldDeliverLaterPositionLastWhenSecondReportCommitsBeforeFirstChangeArrives() {
    var identity = createIdentity();
    var episode = createEpisode();
    var session = openStreamSession(identity, episode);
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var reporter = authTestSupport.profileBearer(identity);

    reportPaused(reporter, session, PAUSED_POSITION_SECONDS);
    reportPaused(reporter, session, LATER_POSITION_SECONDS);

    var first = itemsById(nextChange(subscriber));
    var last = itemsById(nextChange(subscriber));
    assertThat(first).hasSize(3);
    assertThat(positionOf(last, episode.getId())).isEqualTo(LATER_POSITION_SECONDS);
    assertThat(positionOf(last, episode.getSeason().getId())).isEqualTo(LATER_POSITION_SECONDS);
    assertThat(positionOf(last, episode.getSeason().getSeries().getId()))
        .isEqualTo(LATER_POSITION_SECONDS);
  }

  @Test
  @DisplayName("Should deliver the change to every open client of the Profile")
  void shouldDeliverChangeToEveryOpenClientOfProfile() {
    var identity = createIdentity();
    var movie = createMovie();
    var firstSubscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var secondSubscriber = subscribedSocket(authTestSupport.profileBearer(identity));

    markWatched(authTestSupport.profileBearer(identity), movie.getId());

    assertThat(itemsById(nextChange(firstSubscriber))).containsOnlyKeys(movie.getId().toString());
    assertThat(itemsById(nextChange(secondSubscriber))).containsOnlyKeys(movie.getId().toString());
  }

  @Test
  @DisplayName(
      "Should deliver every episode, each season and the series when the Profile marks the series"
          + " watched")
  void shouldDeliverEveryEpisodeEachSeasonAndSeriesWhenProfileMarksSeriesWatched() {
    var identity = createIdentity();
    var firstEpisode = createEpisode();
    var season = firstEpisode.getSeason();
    var series = season.getSeries();
    var library = firstEpisode.getLibrary();
    var secondSeason =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(2).series(series).library(library).build());
    var secondEpisode =
        episodeRepository.saveAndFlush(
            Episode.builder()
                .episodeNumber(1)
                .title("Return")
                .season(secondSeason)
                .library(library)
                .files(Set.of(buildFile(library)))
                .build());
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));

    markWatched(authTestSupport.profileBearer(identity), series.getId());

    var items = itemsById(nextChange(subscriber));
    assertThat(items)
        .containsOnlyKeys(
            firstEpisode.getId().toString(),
            secondEpisode.getId().toString(),
            season.getId().toString(),
            secondSeason.getId().toString(),
            series.getId().toString());
    assertThat(items.values())
        .allSatisfy(item -> assertThat(item.path("watchStatus").asString()).isEqualTo("WATCHED"));
  }

  @Test
  @DisplayName("Should send no change when a repeated progress report changes nothing")
  void shouldSendNoChangeWhenRepeatedProgressReportChangesNothing() {
    var identity = createIdentity();
    var episode = createEpisode();
    var session = openStreamSession(identity, episode);
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var reporter = authTestSupport.profileBearer(identity);
    reportPaused(reporter, session, PAUSED_POSITION_SECONDS);
    assertThat(positionOf(itemsById(nextChange(subscriber)), episode.getId()))
        .isEqualTo(PAUSED_POSITION_SECONDS);

    reportPaused(reporter, session, PAUSED_POSITION_SECONDS);
    reportPaused(reporter, session, LATER_POSITION_SECONDS);

    assertThat(positionOf(itemsById(nextChange(subscriber)), episode.getId()))
        .isEqualTo(LATER_POSITION_SECONDS);
  }

  @Test
  @DisplayName("Should send one change when a stopped report that finishes the episode repeats")
  void shouldSendOneChangeWhenStoppedReportThatFinishesEpisodeRepeats() {
    var identity = createIdentity();
    var episode = createEpisode();
    var sentinel = createMovie();
    var session = openStreamSession(identity, episode);
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));
    var reporter = authTestSupport.profileBearer(identity);
    reportStopped(reporter, session, FINISHED_POSITION_SECONDS);
    var finished = itemsById(nextChange(subscriber)).get(episode.getId().toString());
    assertThat(finished.path("watchStatus").asString()).isEqualTo("WATCHED");

    reportStopped(reporter, session, FINISHED_POSITION_SECONDS);
    markWatched(reporter, sentinel.getId());

    assertThat(itemsById(nextChange(subscriber))).containsOnlyKeys(sentinel.getId().toString());
  }

  @Test
  @DisplayName(
      "Should deliver nothing to another Profile's client when the Profile marks a movie watched")
  void shouldDeliverNothingToAnotherProfilesClientWhenProfileMarksMovieWatched() {
    var identity = createIdentity();
    var otherIdentity = createIdentity();
    var movie = createMovie();
    var sentinel = createMovie();
    var otherSubscriber = subscribedSocket(authTestSupport.profileBearer(otherIdentity));

    markWatched(authTestSupport.profileBearer(identity), movie.getId());
    markWatched(authTestSupport.profileBearer(otherIdentity), sentinel.getId());

    assertThat(itemsById(nextChange(otherSubscriber)))
        .containsOnlyKeys(sentinel.getId().toString());
  }

  @Test
  @DisplayName(
      "Should deliver the movie with its library and unmatched fact when the Profile marks it"
          + " watched")
  void shouldDeliverMovieWithItsLibraryAndUnmatchedFactWhenProfileMarksItWatched() {
    var identity = createIdentity();
    var movie = createMovie();
    var subscriber = subscribedSocket(authTestSupport.profileBearer(identity));

    markWatched(authTestSupport.profileBearer(identity), movie.getId());

    var item = itemsById(nextChange(subscriber)).get(movie.getId().toString());
    assertThat(item.path("__typename").asString()).isEqualTo("Movie");
    assertThat(item.path("watchStatus").asString()).isEqualTo("WATCHED");
    assertThat(item.path("library").path("id").asString())
        .isEqualTo(movie.getLibrary().getId().toString());
    assertThat(item.path("unmatched").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should refuse the subscription with PROFILE_REQUIRED when no Profile is selected")
  void shouldRefuseSubscriptionWithProfileRequiredWhenNoProfileIsSelected() {
    var identity = createIdentity();

    assertRefusedWith(authTestSupport.accountBearer(identity), "PROFILE_REQUIRED");
  }

  @Test
  @DisplayName("Should refuse the subscription with FORBIDDEN when the Account is disabled")
  void shouldRefuseSubscriptionWithForbiddenWhenAccountIsDisabled() {
    var identity = createIdentity();
    var bearer = authTestSupport.profileBearer(identity);
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    assertRefusedWith(bearer, "FORBIDDEN");
  }

  @Test
  @DisplayName(
      "Should close with 4100 and no complete frame when the token that opened the subscription"
          + " expires")
  void shouldCloseWith4100AndNoCompleteFrameWhenTokenThatOpenedSubscriptionExpires() {
    var identity = createIdentity();
    var expiresAt = Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.SECONDS);
    var socket = subscribedSocket(authTestSupport.profileBearerExpiringAt(identity, expiresAt));

    var closure = socket.awaitClosure();

    assertThat(closure.statusCode()).isEqualTo(4100);
    assertThat(Instant.now()).isAfterOrEqualTo(expiresAt);
    assertThat(socket.receivedMessages())
        .noneMatch(message -> "complete".equals(message.path("type").asString()));
    activeSubscriptions.awaitCount(FIELD, 0);
  }

  private void assertRefusedWith(String bearer, String errorCode) {
    var socket = connect(bearer);

    socket.subscribe(OPERATION, SUBSCRIPTION);

    var refusal = socket.nextMessageFor(OPERATION);
    assertThat(refusal.path("type").asString()).isEqualTo("next");
    assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo(errorCode);
    assertThat(socket.nextMessageFor(OPERATION).path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions.count(FIELD)).isZero();
  }

  private GraphQlTransportWsTestClient subscribedSocket(String bearer) {
    var before = activeSubscriptions.count(FIELD);
    var socket = connect(bearer);
    socket.subscribe(OPERATION, SUBSCRIPTION);
    activeSubscriptions.awaitCount(FIELD, (int) before + 1);
    return socket;
  }

  private GraphQlTransportWsTestClient connect(String bearer) {
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    return socket;
  }

  private static JsonNode nextChange(GraphQlTransportWsTestClient socket) {
    var message = socket.nextMessageFor(OPERATION);
    assertThat(message.path("type").asString()).isEqualTo("next");
    assertThat(message.path("payload").path("errors").isMissingNode()).isTrue();
    return message.path("payload").path("data").path(FIELD);
  }

  private static Map<String, JsonNode> itemsById(JsonNode change) {
    return change
        .path("items")
        .valueStream()
        .collect(Collectors.toMap(item -> item.path("id").asString(), item -> item));
  }

  private static int positionOf(Map<String, JsonNode> items, UUID itemId) {
    return items.get(itemId.toString()).path("watchProgress").path("positionSeconds").asInt();
  }

  private void markWatched(String bearer, UUID itemId) {
    var response =
        http.execute(
            bearer,
            "mutation { markWatched(input: {itemId: \"%s\"}) { userErrors { __typename } } }"
                .formatted(itemId));
    var userErrors = response.path("data").path("markWatched").path("userErrors");
    assertThat(userErrors.isArray()).isTrue();
    assertThat(userErrors.isEmpty()).isTrue();
  }

  private void reportPaused(String bearer, StreamSession session, int positionSeconds) {
    reportTimeline(
        bearer,
        "sessionId: \"%s\", positionSeconds: %d, state: PAUSED"
            .formatted(session.getSessionId(), positionSeconds));
  }

  private void reportStopped(String bearer, StreamSession session, int positionSeconds) {
    reportTimeline(
        bearer,
        "sessionId: \"%s\", positionSeconds: %d, state: STOPPED"
            .formatted(session.getSessionId(), positionSeconds));
  }

  private void reportTimeline(String bearer, String timelineArguments) {
    var response =
        http.execute(
            bearer, "mutation { reportStreamSessionTimeline(%s) }".formatted(timelineArguments));
    assertThat(response.path("data").path("reportStreamSessionTimeline").asBoolean()).isTrue();
  }

  private StreamSession openStreamSession(AuthTestSupport.TestIdentity identity, Episode episode) {
    var session =
        defaultSessionBuilder()
            .mediaFileId(episode.getFiles().iterator().next().getId())
            .authority(playbackAuthorityFor(identity.profile().getId()))
            .build();
    runtimeRegistry.save(session);
    streamSessionIds.add(session.getSessionId());
    return session;
  }

  private AuthTestSupport.TestIdentity createIdentity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private Movie createMovie() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Subscribed Movie")
            .titleSort("Subscribed Movie")
            .files(Set.of(buildFile(library)))
            .library(library)
            .build());
  }

  private Episode createEpisode() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var series =
        seriesRepository.saveAndFlush(
            Series.builder()
                .title("Subscribed Series")
                .titleSort("Subscribed Series")
                .library(library)
                .build());
    var season =
        seasonRepository.saveAndFlush(
            Season.builder().seasonNumber(1).series(series).library(library).build());
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(1)
            .title("Pilot")
            .season(season)
            .library(library)
            .files(Set.of(buildFile(library)))
            .build());
  }

  private static MediaFile buildFile(Library library) {
    return MediaFile.builder()
        .libraryId(library.getId())
        .status(MediaFileStatus.MATCHED)
        .filename("subscribed-" + UUID.randomUUID() + ".mkv")
        .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
        .build();
  }
}
