package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakePlaybackReportRepository;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.PositionCursorCodec;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.services.playbackreport.PlaybackReportList;
import com.streamarr.server.services.playbackreport.PlaybackReportService;
import com.streamarr.server.services.playbackreport.PlayerFigures;
import com.streamarr.server.services.playbackreport.PlayerHdrMode;
import com.streamarr.server.services.streaming.PlaybackSessionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      PlaybackReportResolver.class,
      PaginationService.class,
      PositionCursorCodec.class,
      RelayConnectionAdapter.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class,
      PlaybackReportResolverTest.FakePlaybackReports.class
    })
@DisplayName("Playback Report Resolver Tests")
class PlaybackReportResolverTest {

  private static final Instant SHARED_AT = Instant.parse("2026-11-04T20:00:00Z");
  private static final String SHARE =
      """
      mutation($input: SharePlaybackReportInput!) {
        sharePlaybackReport(input: $input) {
          playbackReportId
          userErrors {
            __typename
            ... on MutationError { message }
            ... on InputMutationError { inputPath }
            ... on PlaybackReportNoteTooLongError { maximumLength }
          }
        }
      }
      """;
  private static final String REPORT_FIELDS =
      """
      id sharedAt readAt profileName note requestedChoice playbackChoice mediaFileId
      decisions { formatAttempt reasons }
      player {
        qualityStep { number count height bitrate }
        observedBitrate bufferSeconds droppedFrames stalls
        resolution { width height }
        videoCodec hdrMode
        audio { codec channels dolbyAtmos }
        display { width height hdrMode refreshRate dynamicRangeMatched frameRateMatched }
        audioOutput { route channels }
        screen { width height pixelRatio hdr }
        browser { name version }
      }
      """;

  @Autowired private DgsQueryExecutor executor;
  @Autowired private ObjectMapper mapper;
  @Autowired private FakeAuthorizationService authorizationService;
  @Autowired private FakePlaybackReportRepository repository;
  @Autowired private PlaybackSessionService playbackSessions;
  @Autowired private AtomicReference<AuthenticatedIdentity> caller;

  @TestConfiguration
  static class FakePlaybackReports {

    @Bean
    AtomicReference<AuthenticatedIdentity> caller() {
      return new AtomicReference<>(AuthenticatedIdentityFixture.accountScopedBuilder().build());
    }

    @Bean
    FakeAuthorizationService authorizationService(AtomicReference<AuthenticatedIdentity> caller) {
      return new FakeAuthorizationService(caller::get);
    }

    @Bean
    FakePlaybackReportRepository playbackReportRepository() {
      return new FakePlaybackReportRepository();
    }

    @Bean
    PlaybackSessionService playbackSessionService() {
      return playbackServiceBuilder(new FakeCreatingStreamingService()).build();
    }

    @Bean
    PlaybackReportService playbackReportService(
        FakeAuthorizationService authorizationService,
        PlaybackSessionService playbackSessionService,
        FakePlaybackReportRepository repository) {
      return new PlaybackReportService(
          authorizationService,
          playbackSessionService,
          repository,
          new PlaybackReportList(repository),
          Clock.fixed(SHARED_AT, ZoneOffset.UTC));
    }
  }

  @BeforeEach
  void allowEveryCaller() {
    authorizationService.allowAll();
    repository.deleteSharedBefore(Instant.MAX);
  }

  @Test
  @DisplayName("Should store the input figures and note when a viewer shares a report")
  void shouldStoreInputFiguresAndNoteWhenViewerSharesReport() {
    var playbackSessionId = startPlaybackAsCaller();

    var payload =
        share(
            Map.of(
                "playbackSessionId",
                playbackSessionId.toString(),
                "note",
                "Picture freezes.",
                "player",
                Map.of(
                    "qualityStep",
                    Map.of("number", 2, "count", 3, "height", 1080, "bitrate", 8_000_000),
                    "droppedFrames",
                    4,
                    "hdrMode",
                    "HDR10",
                    "audio",
                    Map.of("codec", "eac3", "channels", 6))));

    assertThat(payload.path("userErrors").size()).isZero();
    assertThat(repository.storedReports())
        .singleElement()
        .satisfies(
            report -> {
              assertThat(report.id().toString())
                  .isEqualTo(payload.path("playbackReportId").asString());
              assertThat(report.note()).contains("Picture freezes.");
              assertThat(report.player())
                  .isEqualTo(
                      PlayerFigures.builder()
                          .qualityStep(
                              Optional.of(
                                  PlayerFigures.QualityStep.builder()
                                      .number(2)
                                      .count(3)
                                      .height(1080)
                                      .bitrate(8_000_000)
                                      .build()))
                          .observedBitrate(OptionalDouble.empty())
                          .bufferSeconds(OptionalDouble.empty())
                          .droppedFrames(OptionalInt.of(4))
                          .stalls(OptionalInt.empty())
                          .resolution(Optional.empty())
                          .videoCodec(Optional.empty())
                          .hdrMode(Optional.of(PlayerHdrMode.HDR10))
                          .audio(
                              Optional.of(
                                  PlayerFigures.PlayerAudio.builder()
                                      .codec("eac3")
                                      .channels(OptionalInt.of(6))
                                      .dolbyAtmos(false)
                                      .build()))
                          .display(Optional.empty())
                          .audioOutput(Optional.empty())
                          .screen(Optional.empty())
                          .browser(Optional.empty())
                          .build());
            });
  }

  @Test
  @DisplayName("Should return an invalid ID error when the playback session ID is malformed")
  void shouldReturnInvalidIdErrorWhenPlaybackSessionIdIsMalformed() {
    caller.set(viewerIdentity());

    var payload = share(Map.of("playbackSessionId", "not-a-uuid", "player", Map.of()));

    assertThat(payload.path("playbackReportId").isNull()).isTrue();
    assertThat(payload.at("/userErrors/0/__typename").asString()).isEqualTo("InvalidIdError");
    assertThat(payload.at("/userErrors/0/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("playbackSessionId")));
  }

  @Test
  @DisplayName("Should name the note and the figure when both are out of range")
  void shouldNameNoteAndFigureWhenBothAreOutOfRange() {
    var playbackSessionId = startPlaybackAsCaller();

    var payload =
        share(
            Map.of(
                "playbackSessionId",
                playbackSessionId.toString(),
                "note",
                "a".repeat(281),
                "player",
                Map.of("screen", Map.of("width", 0, "height", 1, "pixelRatio", 1, "hdr", false))));

    assertThat(payload.path("userErrors"))
        .extracting(error -> error.path("__typename").asString())
        .containsExactly("PlaybackReportNoteTooLongError", "PlayerFigureInvalidError");
    assertThat(payload.at("/userErrors/0/maximumLength").asInt()).isEqualTo(280);
    assertThat(payload.at("/userErrors/0/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("note")));
    assertThat(payload.at("/userErrors/1/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("player", "screen")));
    assertThat(repository.storedReports()).isEmpty();
  }

  @Test
  @DisplayName("Should return the closed error when the viewer owns no such playback session")
  void shouldReturnClosedErrorWhenViewerOwnsNoSuchPlaybackSession() {
    caller.set(viewerIdentity());

    var payload =
        share(Map.of("playbackSessionId", UUID.randomUUID().toString(), "player", Map.of()));

    assertThat(payload.at("/userErrors/0/__typename").asString())
        .isEqualTo("PlaybackSessionClosedError");
    assertThat(repository.storedReports()).isEmpty();
  }

  @Test
  @DisplayName("Should return every figure of a report when a ServerAdmin opens it")
  void shouldReturnEveryFigureOfReportWhenServerAdminOpensIt() {
    var playbackSessionId = startPlaybackAsCaller();
    share(
        Map.of(
            "playbackSessionId",
            playbackSessionId.toString(),
            "player",
            Map.of(
                "display",
                Map.of(
                    "width",
                    3840,
                    "height",
                    2160,
                    "hdrMode",
                    "DOLBY_VISION",
                    "refreshRate",
                    23.976,
                    "dynamicRangeMatched",
                    true,
                    "frameRateMatched",
                    false),
                "audioOutput",
                Map.of("route", "HDMI", "channels", 8))));
    var reportId = repository.storedReports().getFirst().id();

    var report =
        execute("query { playbackReport(id: \"%s\") { %s } }".formatted(reportId, REPORT_FIELDS))
            .at("/data/playbackReport");

    assertThat(report.path("sharedAt").asString()).isEqualTo(SHARED_AT.toString());
    assertThat(report.path("readAt").isNull()).isTrue();
    assertThat(report.path("note").isNull()).isTrue();
    assertThat(report.path("requestedChoice").asString()).isEqualTo("AUTO");
    assertThat(report.at("/player/display"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "width",
                    3840,
                    "height",
                    2160,
                    "hdrMode",
                    "DOLBY_VISION",
                    "refreshRate",
                    23.976,
                    "dynamicRangeMatched",
                    true,
                    "frameRateMatched",
                    false)));
    assertThat(report.at("/player/audioOutput"))
        .isEqualTo(mapper.valueToTree(Map.of("route", "HDMI", "channels", 8)));
    assertThat(report.at("/player/qualityStep").isNull()).isTrue();
    assertThat(report.at("/player/droppedFrames").isNull()).isTrue();
    assertThat(report.at("/player/screen").isNull()).isTrue();
    assertThat(report.path("decisions").size()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should list reports with their unread count when a ServerAdmin reads them")
  void shouldListReportsWithUnreadCountWhenServerAdminReadsThem() {
    shareEmptyReport();
    shareEmptyReport();

    var response =
        execute(
            """
            query {
              playbackReports(first: 1) { edges { node { id } cursor } pageInfo { hasNextPage } }
              unreadPlaybackReportCount
            }
            """);

    assertThat(response.at("/data/playbackReports/edges").size()).isEqualTo(1);
    assertThat(response.at("/data/playbackReports/pageInfo/hasNextPage").asBoolean()).isTrue();
    assertThat(response.at("/data/unreadPlaybackReportCount").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should return the report as read when a ServerAdmin marks it read")
  void shouldReturnReportAsReadWhenServerAdminMarksItRead() {
    shareEmptyReport();
    var reportId = repository.storedReports().getFirst().id();

    var payload = markRead(reportId.toString());

    assertThat(payload.at("/playbackReport/readAt").asString()).isEqualTo(SHARED_AT.toString());
    assertThat(payload.path("userErrors").size()).isZero();
  }

  @Test
  @DisplayName("Should return the not found error when no report has the ID")
  void shouldReturnNotFoundErrorWhenNoReportHasTheId() {
    var payload = markRead(UUID.randomUUID().toString());

    assertThat(payload.path("playbackReport").isNull()).isTrue();
    assertThat(payload.at("/userErrors/0/__typename").asString())
        .isEqualTo("PlaybackReportNotFoundError");
    assertThat(payload.at("/userErrors/0/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of("playbackReportId")));
  }

  @Test
  @DisplayName("Should return an invalid ID error when the report ID is malformed")
  void shouldReturnInvalidIdErrorWhenReportIdIsMalformed() {
    var payload = markRead("not-a-uuid");

    assertThat(payload.at("/userErrors/0/__typename").asString()).isEqualTo("InvalidIdError");
  }

  @Test
  @DisplayName("Should expose a forbidden top-level error when the caller is not a ServerAdmin")
  void shouldExposeForbiddenTopLevelErrorWhenCallerIsNotServerAdmin() {
    authorizationService.denyAll();

    var list = execute("query { playbackReports { edges { cursor } } }");
    var one = execute("query { playbackReport(id: \"%s\") { id } }".formatted(UUID.randomUUID()));
    var count = execute("query { unreadPlaybackReportCount }");
    var mark =
        execute(
            """
            mutation { markPlaybackReportRead(input: {playbackReportId: "%s"}) {
              playbackReport { id } } }
            """
                .formatted(UUID.randomUUID()));

    assertThat(List.of(list, one, count, mark))
        .allSatisfy(
            response ->
                assertThat(response.at("/errors/0/extensions/code").asString())
                    .isEqualTo("FORBIDDEN"));
  }

  private UUID startPlaybackAsCaller() {
    var start = startPlaybackBuilder().build();
    caller.set(start.identity());
    return acceptedPlayback(playbackSessions.start(start)).playbackSessionId();
  }

  private void shareEmptyReport() {
    var playbackSessionId = startPlaybackAsCaller();
    share(Map.of("playbackSessionId", playbackSessionId.toString(), "player", Map.of()));
  }

  private static AuthenticatedIdentity viewerIdentity() {
    return startPlaybackBuilder().build().identity();
  }

  private JsonNode share(Map<String, Object> input) {
    var response =
        mapper.<JsonNode>valueToTree(
            executor.execute(SHARE, Map.of("input", input)).toSpecification());
    assertThat(response.has("errors")).isFalse();
    return response.at("/data/sharePlaybackReport");
  }

  private JsonNode markRead(String playbackReportId) {
    var response =
        mapper.<JsonNode>valueToTree(
            executor
                .execute(
                    """
                    mutation($input: MarkPlaybackReportReadInput!) {
                      markPlaybackReportRead(input: $input) {
                        playbackReport { id readAt }
                        userErrors { __typename ... on InputMutationError { inputPath } }
                      }
                    }
                    """,
                    Map.of("input", Map.of("playbackReportId", playbackReportId)))
                .toSpecification());
    assertThat(response.has("errors")).isFalse();
    return response.at("/data/markPlaybackReportRead");
  }

  private JsonNode execute(String document) {
    return mapper.valueToTree(executor.execute(document).toSpecification());
  }
}
