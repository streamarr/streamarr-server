package com.streamarr.server.services.identity;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitWaitersBehind;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ControlledClockConfiguration;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import com.streamarr.server.support.TestFixtureWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The requests about an offer whose time runs out (ADR 0063 §Requests), on a clock the tests move
 * past the offer's expiry: a request reads as expired from its expiry on, and an offer that stops
 * waiting after it resolves its requests as expired, at the expiry. Offers last five minutes here,
 * so the moved clock stays inside the lifetime of the tokens and sockets the tests open.
 */
@Tag("IntegrationTest")
@DisplayName("Offer Expiry Notifications Integration Tests")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "auth.credential-codes.invitation-ttl=5m")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ControlledClockConfiguration.class)
class OfferExpiryNotificationsIT extends AbstractIntegrationTest {

  private static final String FIELD = "notificationsChanged";
  private static final String OPERATION = "notifications";
  private static final String OFFERED = "PROFILE_SHARE_OFFERED";
  private static final String SUBSCRIPTION =
      """
      subscription {
        notificationsChanged {
          unreadNotificationCount
          notifications {
            notificationId
            edge {
              cursor orderKey
              node {
                __typename id unread message
                ... on RequestNotification { expiresAt resolution resolvedAt }
              }
            }
          }
        }
      }
      """;

  // The same edge fields as SUBSCRIPTION, read through the notifications query.
  private static final String NOTIFICATIONS =
      """
      query {
        unreadNotificationCount
        notifications(first: 10) {
          edges {
            cursor orderKey
            node {
              __typename id unread message
              ... on RequestNotification { expiresAt resolution resolvedAt }
            }
          }
        }
      }
      """;

  private static final ObjectMapper JSON = new ObjectMapper();

  @LocalServerPort private int port;
  @Autowired private MutableClock clock;
  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileShareExpirySweeper expirySweeper;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private MeterRegistry meterRegistry;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private ShareSteps steps;
  private StoredNotificationRows rows;
  private TestIdentity sam;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;

  @BeforeEach
  void setUp() {
    clock.advance(Duration.between(clock.instant(), Instant.now()));
    rows = new StoredNotificationRows(dsl);
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    steps =
        ShareSteps.builder()
            .sharing(sharing)
            .auth(authTestSupport)
            .profiles(profileRepository)
            .managers(profileManagerRepository)
            .shares(shareRepository)
            .accounts(userAccountRepository)
            .transactions(transactionTemplate)
            .build();
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
  }

  @AfterEach
  void tearDown() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    activeSubscriptions.awaitNoneActive();
    rows.deleteOf(identities.stream().map(OfferExpiryNotificationsIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should resolve the requests as expired at the offer's expiry when the expiry sweep stores it")
  void shouldResolveTheRequestsAsExpiredAtTheOffersExpiryWhenTheSweepStoresIt() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    runOut(offer);

    expirySweeper.sweep();

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertExpiredAtTheExpiry(offer);
  }

  @Test
  @DisplayName(
      "Should resolve the requests as expired at the offer's expiry when a rejection comes after"
          + " the offer's time ran out")
  void shouldResolveTheRequestsAsExpiredAtTheOffersExpiryWhenARejectionComesAfterItsTimeRanOut() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    runOut(offer);
    // A ServerAdmin still reads an offer whose time ran out, so its rejection reaches the offer.
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    var outcome = sharing.rejectProfileShare(identityOf(serverAdmin), offer.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertExpiredAtTheExpiry(offer);
  }

  @Test
  @DisplayName("Should tell nobody anything new when the offer expires")
  void shouldTellNobodyAnythingNewWhenTheOfferExpires() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    runOut(offer);

    expirySweeper.sweep();

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(rows.about(SubjectReference.profileShare(offer.getId())))
        .extracting(Row::kind)
        .containsOnly(OFFERED);
  }

  @Test
  @DisplayName(
      "Should tell nobody anything new when a rejection comes after the offer's time ran out")
  void shouldTellNobodyAnythingNewWhenARejectionComesAfterTheOffersTimeRanOut() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    runOut(offer);
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    sharing.rejectProfileShare(identityOf(serverAdmin), offer.getId());

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(rows.about(SubjectReference.profileShare(offer.getId())))
        .extracting(Row::kind)
        .containsOnly(OFFERED);
  }

  @Test
  @DisplayName(
      "Should leave a request out of the count and read it as expired at its expiry when its"
          + " offer's time ran out and the sweep has not stored it")
  void shouldLeaveARequestOutOfTheCountAndReadItAsExpiredWhenItsOffersTimeRanOutBeforeTheSweep()
      throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    runOut(offer);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(false))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("EXPIRED"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.resolvedAt")
                .value(stored(offer).getExpiresAt().toString()));
  }

  @Test
  @DisplayName(
      "Should keep a request that reads as expired when its recipient is demoted, as the history"
          + " of an offer that is over")
  void shouldKeepARequestThatReadsAsExpiredWhenItsRecipientIsDemoted() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    runOut(offer);

    demote(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("EXPIRED"));
  }

  @Test
  @DisplayName("Should deliver the request's expiry when the sweep stores the offer's expiry")
  void shouldDeliverTheRequestsExpiryWhenTheSweepStoresTheOffersExpiry() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    runOut(offer);

    expirySweeper.sweep();

    var expired = nextChange(socket);
    assertThat(onlyChange(expired).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, offer).id().toString());
    assertThat(nodeOf(expired).path("resolution").asString()).isEqualTo("EXPIRED");
    assertThat(expired.path("unreadNotificationCount").asInt()).isZero();
    assertMatchesAFreshQuery(expired, riversideAdmin);
  }

  /**
   * The acceptance takes its instant a second before the offer's expiry and then waits behind the
   * share's row, which the test holds, while the clock moves past the expiry. The request reads as
   * expired while the acceptance waits; once it commits, the request is decided at the instant the
   * acceptance took.
   */
  @Test
  @DisplayName(
      "Should resolve the requests as decided at the acceptance, and push the decision, when the"
          + " acceptance waited past the offer's expiry")
  void shouldResolveTheRequestsAsDecidedAtTheAcceptanceWhenTheAcceptanceWaitedPastTheExpiry()
      throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    clock.advance(Duration.between(clock.instant(), stored(offer).getExpiresAt().minusSeconds(1)));
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    var shareRow =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("profile_household_share")
            .rowId(offer.getId())
            .build();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var share = lockRow(shareRow)) {
      var acceptance = executor.submit(() -> steps.accept(riverside, offer));
      awaitWaitersBehind(jdbcTemplate, share.backendPid(), 1);
      runOut(offer);
      mockMvc
          .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("EXPIRED"));

      share.release();
      acceptance.get(10, TimeUnit.SECONDS);
    }

    var accepted = stored(offer);
    assertThat(accepted.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(accepted.getDecidedAt()).isBefore(accepted.getExpiresAt());
    assertThat(requestsAbout(offer))
        .hasSize(2)
        .allSatisfy(
            request -> {
              assertThat(request.resolution()).contains("DECIDED");
              assertThat(request.resolvedAt()).contains(accepted.getDecidedAt());
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              "Kai", Optional.of(ProfileShareAnswer.ACCEPTED))));
            });
    var decided = nextChange(socket);
    assertThat(nodeOf(decided).path("resolution").asString()).isEqualTo("DECIDED");
    assertMatchesAFreshQuery(decided, riversideAdmin);
  }

  private Profile kai() {
    return steps.profileManagedBy("Kai", sam, sam);
  }

  /** Moves the clock a second past the offer's expiry, as if its time ran out. */
  private void runOut(ProfileHouseholdShare offer) {
    clock.advance(Duration.between(clock.instant(), stored(offer).getExpiresAt().plusSeconds(1)));
  }

  private void demote(TestIdentity householdAdmin) {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    assertThat(
            accountAdministration.revokeHouseholdAdmin(
                authTestSupport.freshIdentityOf(serverAdmin), householdAdmin.account().getId()))
        .isInstanceOf(Outcome.Accepted.class);
  }

  // Every recipient's request resolves at once, as expired, at the offer's expiry.
  private void assertExpiredAtTheExpiry(ProfileHouseholdShare offer) {
    var stored = stored(offer);
    var profileName = profileRepository.findById(stored.getProfileId()).orElseThrow().getName();
    assertThat(requestsAbout(offer))
        .hasSize(2)
        .allSatisfy(
            request -> {
              assertThat(request.resolution()).contains("EXPIRED");
              assertThat(request.resolvedAt()).contains(stored.getExpiresAt());
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(new ProfileShareOfferedSnapshot(profileName, Optional.empty())));
            });
  }

  private ProfileHouseholdShare stored(ProfileHouseholdShare share) {
    return shareRepository.findById(share.getId()).orElseThrow();
  }

  private List<Row> requestsAbout(ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.kind().equals(OFFERED))
        .toList();
  }

  private Row requestFor(TestIdentity recipient, ProfileHouseholdShare share) {
    return requestsAbout(share).stream()
        .filter(row -> row.recipient().equals(accountOf(recipient)))
        .findFirst()
        .orElseThrow();
  }

  /** Each pushed edge, and the pushed unread count, are the ones a fresh query reads. */
  private void assertMatchesAFreshQuery(JsonNode change, TestIdentity reader) throws Exception {
    var response =
        mockMvc
            .perform(graphqlRequest(authTestSupport.accountBearer(reader), NOTIFICATIONS))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var fresh = JSON.readTree(response).path("data");
    assertThat(change.path("unreadNotificationCount"))
        .isEqualTo(fresh.path("unreadNotificationCount"));
    for (var pushed : change.path("notifications")) {
      var id = pushed.path("notificationId").asString();
      var freshEdge =
          fresh
              .path("notifications")
              .path("edges")
              .valueStream()
              .filter(edge -> edge.path("node").path("id").asString().equals(id))
              .findFirst()
              .orElseThrow();
      assertThat(pushed.path("edge")).isEqualTo(freshEdge);
    }
  }

  private static JsonNode onlyChange(JsonNode change) {
    var notifications = change.path("notifications");
    assertThat(notifications.size()).as("notifications in %s", change).isEqualTo(1);
    return notifications.path(0);
  }

  private static JsonNode nodeOf(JsonNode change) {
    return onlyChange(change).path("edge").path("node");
  }

  private static JsonNode nextChange(GraphQlTransportWsTestClient socket) {
    var message = socket.nextMessageFor(OPERATION);
    assertThat(message.path("type").asString()).as("message %s", message).isEqualTo("next");
    var payload = message.path("payload");
    assertThat(payload.path("errors").isMissingNode()).as("errors in %s", payload).isTrue();
    return payload.path("data").path(FIELD);
  }

  private GraphQlTransportWsTestClient subscribedSocket(String bearer) {
    var before = activeSubscriptions.count(FIELD);
    var socket = GraphQlTransportWsTestClient.connectWithBearer(port, bearer);
    sockets.add(socket);
    socket.subscribe(OPERATION, SUBSCRIPTION);
    activeSubscriptions.awaitCount(FIELD, (int) before + 1);
    return socket;
  }

  private AuthenticatedIdentity identityOf(TestIdentity identity) {
    return authTestSupport.identityOf(identity);
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
