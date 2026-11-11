package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Each committed change to an Account's Notifications reaches every open socket of that Account,
 * with the edge the notifications query shows it, and never a Notification the guard hides.
 */
@Tag("IntegrationTest")
@DisplayName("Notifications Subscription Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationsSubscriptionIT extends AbstractIntegrationTest {

  private static final String FIELD = "notificationsChanged";
  private static final String OPERATION = "notifications";
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
                ... on RequestNotification { resolution }
                ... on ProfileShareAnsweredNotification { answer }
              }
            }
          }
        }
      }
      """;

  @LocalServerPort private int port;
  @Autowired private ProfileSharingService sharing;
  @Autowired private NotificationService notificationService;
  @Autowired private ProfileShareExpirySweeper expirySweeper;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private MeterRegistry meterRegistry;

  private final List<TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;

  @BeforeEach
  void setUp() {
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
    theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
  }

  @AfterEach
  void tearDown() {
    sockets.forEach(GraphQlTransportWsTestClient::close);
    activeSubscriptions.awaitNoneActive();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver the new request and then its resolution when an offer is made and rejected")
  void shouldDeliverTheNewRequestAndThenItsResolutionWhenAnOfferIsMadeAndRejected() {
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var offer = steps.offer(sam, kai(), riverside);
    var made = nextChange(socket);
    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());
    var resolved = nextChange(socket);

    var request = requestFor(riversideAdmin, offer);
    assertThat(made.path("unreadNotificationCount").asInt()).isEqualTo(1);
    assertThat(onlyChange(made).path("notificationId").asString())
        .isEqualTo(request.getId().toString());
    assertThat(nodeOf(made).path("__typename").asString())
        .isEqualTo("ProfileShareOfferedNotification");
    assertThat(nodeOf(made).path("unread").asBoolean()).isTrue();
    assertThat(onlyChange(made).path("edge").path("cursor").asString()).isNotEmpty();
    assertThat(resolved.path("unreadNotificationCount").asInt()).isZero();
    assertThat(nodeOf(resolved).path("unread").asBoolean()).isFalse();
    assertThat(nodeOf(resolved).path("resolution").asString()).isEqualTo("DECIDED");
  }

  @Test
  @DisplayName("Should deliver the request's expiry when the sweep stores the offer's expiry")
  void shouldDeliverTheRequestsExpiryWhenTheSweepStoresTheOffersExpiry() {
    var offer = steps.offer(sam, kai(), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    steps.lapse(offer);
    expirySweeper.sweep();

    var expired = nextChange(socket);
    assertThat(onlyChange(expired).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, offer).getId().toString());
    assertThat(nodeOf(expired).path("resolution").asString()).isEqualTo("EXPIRED");
    assertThat(expired.path("unreadNotificationCount").asInt()).isZero();
  }

  @Test
  @DisplayName(
      "Should deliver the mark to every open socket of the Account when its recipient marks the"
          + " news read")
  void shouldDeliverTheMarkToEveryOpenSocketOfTheAccountWhenItsRecipientMarksTheNewsRead() {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    steps.accept(riverside, offer);
    var news = requestFor(theo, offer);
    var phone = subscribedSocket(authTestSupport.accountBearer(theo));
    var laptop = subscribedSocket(authTestSupport.accountBearer(theo));

    notificationService.markNotificationRead(authTestSupport.identityOf(theo), news.getId());

    assertThat(List.of(nextChange(phone), nextChange(laptop)))
        .allSatisfy(
            marked -> {
              assertThat(onlyChange(marked).path("notificationId").asString())
                  .isEqualTo(news.getId().toString());
              assertThat(nodeOf(marked).path("__typename").asString())
                  .isEqualTo("ProfileShareAnsweredNotification");
              assertThat(nodeOf(marked).path("unread").asBoolean()).isFalse();
              assertThat(marked.path("unreadNotificationCount").asInt()).isZero();
            });
  }

  @Test
  @DisplayName("Should deliver nothing about a request when the guard hides it from its recipient")
  void shouldDeliverNothingAboutARequestWhenTheGuardHidesItFromItsRecipient() {
    var hidden = steps.offer(sam, kai(), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDemoteToHouseholdMember(riversideAdmin.account().getId()));

    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), hidden.getId());
    var ash = steps.profileManagedBy("Ash", riverside, riversideAdmin);
    var shown = steps.offer(riversideAdmin, ash, sam);
    steps.accept(sam, shown);

    var first = nextChange(socket);
    assertThat(nodeOf(first).path("__typename").asString())
        .isEqualTo("ProfileShareAnsweredNotification");
    assertThat(onlyChange(first).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, shown).getId().toString());
  }

  @Test
  @DisplayName(
      "Should end notificationsChanged with FORBIDDEN at the next change when the Account is"
          + " disabled after subscribing")
  void shouldEndNotificationsChangedWithForbiddenWhenTheAccountIsDisabledAfterSubscribing() {
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    var account = userAccountRepository.findById(riversideAdmin.account().getId()).orElseThrow();
    account.setEnabled(false);
    userAccountRepository.saveAndFlush(account);

    steps.offer(sam, kai(), riverside);

    var ending = socket.nextMessageFor(OPERATION);
    assertThat(ending.path("type").asString()).isEqualTo("error");
    assertThat(ending.path("payload").path(0).path("extensions").path("code").asString())
        .isEqualTo("FORBIDDEN");
    activeSubscriptions.awaitCount(FIELD, 0);
  }

  @Test
  @DisplayName("Should refuse notificationsChanged with FORBIDDEN when the session is device-bound")
  void shouldRefuseNotificationsChangedWithForbiddenWhenTheSessionIsDeviceBound() {
    var socket = connect(authTestSupport.deviceBoundProfileBearer(riversideAdmin));

    socket.subscribe(OPERATION, SUBSCRIPTION);

    var refusal = socket.nextMessageFor(OPERATION);
    assertThat(refusal.path("type").asString()).isEqualTo("next");
    assertThat(firstErrorCode(refusal.path("payload"))).isEqualTo("FORBIDDEN");
    assertThat(socket.nextMessageFor(OPERATION).path("type").asString()).isEqualTo("complete");
    assertThat(activeSubscriptions.count(FIELD)).isZero();
  }

  private Profile kai() {
    return steps.profileManagedBy("Kai", sam, sam);
  }

  private Notification requestFor(TestIdentity recipient, ProfileHouseholdShare share) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(share.getId()))
        .filter(
            notification ->
                notification.getRecipientAccountId().equals(recipient.account().getId()))
        .findFirst()
        .orElseThrow();
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

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
