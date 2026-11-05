package com.streamarr.server.graphql.subscriptions;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.CreateStreamSessionCommand;
import com.streamarr.server.services.streaming.CreateStreamSessionRejection;
import com.streamarr.server.services.streaming.PlaybackRequest;
import com.streamarr.server.services.streaming.RuntimeStreamSessionRegistry;
import com.streamarr.server.services.streaming.StreamingService;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlHttpTestClient;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;

@Tag("IntegrationTest")
@DisplayName("Active Stream Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ActiveStreamSubscriptionIT.RegisteringStreams.class)
class ActiveStreamSubscriptionIT extends AbstractIntegrationTest {

  private static final String FIELD = "activeStreamsChanged";
  private static final String SUBSCRIPTION =
      """
      subscription {
        activeStreamsChanged {
          playbackSessionId
          activeStream {
            profile { id name } title positionSeconds state workers
            decision { formatAttempt video { delivery } reasons }
          }
        }
      }
      """;
  private static final String CAPABILITIES =
      """
      {video: [{codecFamily: H264, codecProfiles: [{profile: H264_HIGH, maxLevel: 41}], \
      maxBitDepth: 8, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 60, compatibleBases: [SDR], \
      dolbyVisionProfiles: [], hdr10Plus: false, hls: true}], \
      audio: [{codec: AAC, maxChannels: 2, hls: true}]}""";

  @LocalServerPort private int port;

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private UserAccountRepository userAccountRepository;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private final List<String> openPlaybacks = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private GraphQlHttpTestClient http;
  private AuthTestSupport.TestIdentity viewer;

  /** Starts stream sessions without a worker, so that playback sessions open in this test. */
  @TestConfiguration
  static class RegisteringStreams {

    @Bean
    @Primary
    StreamingService registeringStreamingService(RuntimeStreamSessionRegistry registry) {
      return new RegisteringStreamingService(registry);
    }
  }

  private record RegisteringStreamingService(RuntimeStreamSessionRegistry registry)
      implements StreamingService {

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      var session =
          defaultSessionBuilder()
              .sessionId(command.streamSessionId())
              .mediaFileId(command.mediaFileId())
              .authority(command.identity().playbackAuthority())
              .options(command.options())
              .build();
      registry.save(session);
      return Outcome.accepted(session);
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return registry.findById(request.streamSessionId());
    }

    @Override
    public void destroySession(UUID sessionId) {
      registry.removeById(sessionId);
    }

    @Override
    public boolean destroySessionIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
      return registry.removeIfLastAccessedBefore(sessionId, cutoff);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      destroySession(sessionId);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return registry.findAll();
    }

    @Override
    public int getActiveSessionCount() {
      return registry.count();
    }
  }

  @BeforeEach
  void openClients() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    http = new GraphQlHttpTestClient(URI.create("http://localhost:%d/graphql".formatted(port)));
    viewer = authTestSupport.createIdentity();
    identities.add(viewer);
  }

  @AfterEach
  void releaseResources() {
    var viewerBearer = authTestSupport.profileBearer(viewer);
    openPlaybacks.forEach(id -> http.execute(viewerBearer, destroyMutation(id)));
    sockets.forEach(GraphQlTransportWsTestClient::close);
    http.close();
    activeSubscriptions.awaitNoneActive();
    identities.forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver a stream's start, pause and end and list it while it plays when a ServerAdmin"
          + " subscribes first")
  void shouldDeliverAStreamsStartPauseAndEndAndListItWhileItPlaysWhenAServerAdminSubscribesFirst() {
    var admin = adminBearer();
    var socket = connect(admin);
    socket.subscribe("streams", SUBSCRIPTION);
    activeSubscriptions.awaitCount(FIELD, 1);
    var viewerBearer = authTestSupport.profileBearer(viewer);
    var movieFile = savedMovieFile();

    var created = http.execute(viewerBearer, createMutation(movieFile));
    var playback = created.path("data").path("createPlaybackSession").path("playbackSession");
    var playbackId = playback.path("id").asString();
    openPlaybacks.add(playbackId);
    var started = nextChange(socket, playbackId).path("activeStream");
    var streamId =
        playback.path("currentFormatAttempt").path("streamSession").path("id").asString();
    http.execute(viewerBearer, timelineMutation(streamId, 30, "PAUSED"));
    var paused = nextChange(socket, playbackId).path("activeStream");
    var listed = http.execute(admin, "query { activeStreams { playbackSessionId state } }");
    http.execute(viewerBearer, destroyMutation(playbackId));
    var ended = nextChange(socket, playbackId);

    assertThat(started.path("profile").path("id").asString())
        .isEqualTo(viewer.profile().getId().toString());
    assertThat(started.path("profile").path("name").asString())
        .isEqualTo(viewer.profile().getName());
    assertThat(started.path("title").asString()).isEqualTo("Streamed Movie");
    assertThat(started.path("decision").path("formatAttempt").asInt()).isEqualTo(1);
    assertThat(started.path("decision").path("video").path("delivery").asString())
        .isEqualTo("COPY");
    assertThat(paused.path("positionSeconds").asInt()).isEqualTo(30);
    assertThat(paused.path("state").asString()).isEqualTo("PAUSED");
    assertThat(listed.path("data").path("activeStreams").valueStream())
        .filteredOn(stream -> playbackId.equals(stream.path("playbackSessionId").asString()))
        .singleElement()
        .satisfies(stream -> assertThat(stream.path("state").asString()).isEqualTo("PAUSED"));
    assertThat(ended.path("activeStream").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "Should refuse activeStreamsChanged and activeStreams with FORBIDDEN when the Profile is not"
          + " a ServerAdmin")
  void shouldRefuseActiveStreamsWithForbiddenWhenTheProfileIsNotAServerAdmin() {
    assertRefused(authTestSupport.profileBearer(viewer));
  }

  @Test
  @DisplayName(
      "Should refuse activeStreamsChanged and activeStreams with FORBIDDEN when a ServerAdmin's"
          + " session is device-bound")
  void shouldRefuseActiveStreamsWithForbiddenWhenAServerAdminsSessionIsDeviceBound() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);

    assertRefused(authTestSupport.deviceBoundProfileBearer(identity));
  }

  @ParameterizedTest
  @EnumSource(Revocation.class)
  @DisplayName(
      "Should end activeStreamsChanged with FORBIDDEN before delivering a stream when the"
          + " ServerAdmin loses authority after subscribing")
  void shouldEndActiveStreamsChangedWithForbiddenWhenTheServerAdminLosesAuthorityAfterSubscribing(
      Revocation revocation) {
    var admin = authTestSupport.createAdminIdentity();
    identities.add(admin);
    var socket = connect(authTestSupport.profileBearer(admin));
    socket.subscribe("streams", SUBSCRIPTION);
    activeSubscriptions.awaitCount(FIELD, 1);
    revocation.apply(userAccountRepository, admin);

    var created =
        http.execute(authTestSupport.profileBearer(viewer), createMutation(savedMovieFile()));
    var playbackId =
        created.path("data").path("createPlaybackSession").path("playbackSession").path("id");
    openPlaybacks.add(playbackId.asString());
    var ending = nextMessageOtherThanAnEarlierStream(socket, playbackId.asString());

    assertThat(ending.path("type").asString()).isEqualTo("error");
    assertThat(ending.path("payload").path(0).path("extensions").path("code").asString())
        .isEqualTo("FORBIDDEN");
    activeSubscriptions.awaitNoneActive();
  }

  enum Revocation {
    DEMOTED {
      @Override
      void revoke(UserAccount account) {
        account.setServerAdmin(false);
      }
    },
    DISABLED {
      @Override
      void revoke(UserAccount account) {
        account.setEnabled(false);
      }
    };

    abstract void revoke(UserAccount account);

    void apply(UserAccountRepository accounts, AuthTestSupport.TestIdentity identity) {
      var account = accounts.findById(identity.account().getId()).orElseThrow();
      revoke(account);
      accounts.saveAndFlush(account);
    }
  }

  // A stream that another test left may still announce itself before the revocation lands, but no
  // message may carry this test's stream.
  private static JsonNode nextMessageOtherThanAnEarlierStream(
      GraphQlTransportWsTestClient socket, String playbackId) {
    while (true) {
      var message = socket.nextMessageFor("streams");
      if (!"next".equals(message.path("type").asString())) {
        return message;
      }

      var change = message.path("payload").path("data").path(FIELD);
      assertThat(change.path("playbackSessionId").asString())
          .as("a change of this test's stream reached a subscriber without authority")
          .isNotEqualTo(playbackId);
    }
  }

  private void assertRefused(String bearer) {
    var socket = connect(bearer);
    socket.subscribe("streams", SUBSCRIPTION);

    var refusal = socket.nextMessageFor("streams");
    var queried = http.execute(bearer, "query { activeStreams { playbackSessionId } }");

    assertThat(refusal.path("type").asString()).isEqualTo("next");
    assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("FORBIDDEN");
    assertThat(socket.nextMessageFor("streams").path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions.count(FIELD)).isZero();
    assertThat(firstErrorCode(queried)).isEqualTo("FORBIDDEN");
  }

  // The next change of one playback session, skipping changes of streams that other tests left.
  private static JsonNode nextChange(GraphQlTransportWsTestClient socket, String playbackId) {
    while (true) {
      var payload = socket.nextMessageFor("streams").path("payload");
      assertThat(payload.path("errors").isMissingNode()).as("errors in %s", payload).isTrue();
      var change = payload.path("data").path(FIELD);
      if (playbackId.equals(change.path("playbackSessionId").asString())) {
        return change;
      }
    }
  }

  private UUID savedMovieFile() {
    Library library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Streamed Movie")
                .titleSort("Streamed Movie")
                .library(library)
                .files(
                    Set.of(
                        MediaFile.builder()
                            .libraryId(library.getId())
                            .status(MediaFileStatus.MATCHED)
                            .filename("streamed.mkv")
                            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
                            .build()))
                .build());
    return movie.getFiles().iterator().next().getId();
  }

  private String adminBearer() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    return authTestSupport.profileBearer(identity);
  }

  private GraphQlTransportWsTestClient connect(String bearer) {
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    return socket;
  }

  private static String createMutation(UUID mediaFileId) {
    return """
        mutation {
          createPlaybackSession(input: {mediaFileId: "%s", capabilities: %s}) {
            playbackSession {
              id
              currentFormatAttempt { ... on ReadyPlaybackFormatAttempt { streamSession { id } } }
            }
          }
        }
        """
        .formatted(mediaFileId, CAPABILITIES);
  }

  private static String timelineMutation(String streamId, int positionSeconds, String state) {
    return """
        mutation {
          reportStreamSessionTimeline(sessionId: "%s", positionSeconds: %d, state: %s)
        }
        """
        .formatted(streamId, positionSeconds, state);
  }

  private static String destroyMutation(String playbackId) {
    return """
        mutation {
          destroyPlaybackSession(input: {playbackSessionId: "%s"}) { playbackSessionId }
        }
        """
        .formatted(playbackId);
  }
}
