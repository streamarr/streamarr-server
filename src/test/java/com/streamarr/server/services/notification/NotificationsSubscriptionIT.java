package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.services.identity.AccountAdministrationService;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareRejections;
import com.streamarr.server.services.mutation.Outcome;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

  // The same edge fields as SUBSCRIPTION, read through the notifications query.
  private static final String FRESH_QUERY =
      """
      query {
        unreadNotificationCount
        notifications(first: 50) {
          edges {
            cursor orderKey
            node {
              __typename id unread message
              ... on RequestNotification { resolution }
              ... on ProfileShareAnsweredNotification { answer }
            }
          }
        }
      }
      """;

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CONNECTED_EMAIL = "ollie.connected@example.com";

  @LocalServerPort private int port;
  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private AccountInvitationService accountInvitations;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private ProfileManagerAdministrationService profileManagement;
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
  private ProfileConnections connections;

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
    connections =
        ProfileConnections.builder()
            .credentialIssuance(credentialIssuance)
            .accountInvitations(accountInvitations)
            .invitations(invitationRepository)
            .accounts(userAccountRepository)
            .auth(authTestSupport)
            .recipientEmail(CONNECTED_EMAIL)
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
    connections.deleteConnected();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should deliver the new request and then its resolution when an offer is made and rejected")
  void shouldDeliverTheNewRequestAndThenItsResolutionWhenAnOfferIsMadeAndRejected()
      throws Exception {
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var offer = steps.offer(sam, kai(), riverside);
    var made = nextChange(socket);
    assertMatchesAFreshQuery(made, riversideAdmin);
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
    assertMatchesAFreshQuery(resolved, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the answer to the offering side and the decision to the Household's side"
          + " when an offer is accepted")
  void shouldDeliverTheAnswerAndTheDecisionWhenAnOfferIsAccepted() throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(theo));
    var householdSide = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    steps.accept(riverside, offer);

    var answered = nextChange(offeringSide);
    assertThat(nodeOf(answered).path("answer").asString()).isEqualTo("ACCEPTED");
    assertMatchesAFreshQuery(answered, theo);
    var decided = nextChange(householdSide);
    assertThat(nodeOf(decided).path("resolution").asString()).isEqualTo("DECIDED");
    assertMatchesAFreshQuery(decided, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when its acceptance finds the Account that made the"
          + " offer no longer authorized")
  void shouldDeliverTheRequestsWithdrawalWhenItsAcceptanceFindsTheOffererNoLongerAuthorized()
      throws Exception {
    var offer = steps.offer(theo, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    // Disabled in the database alone, so the offer still waits when the Household accepts it.
    var offerer = userAccountRepository.findById(theo.account().getId()).orElseThrow();
    offerer.setEnabled(false);
    userAccountRepository.saveAndFlush(offerer);

    var outcome = sharing.acceptProfileShare(authTestSupport.identityOf(riverside), offer.getId());

    assertThat(outcome).isEqualTo(Outcome.rejected(new ShareRejections.OfferInvalidated()));
    assertWithdrawn(nextChange(socket), riversideAdmin, offer);
  }

  @Test
  @DisplayName("Should deliver the request's withdrawal when its offer is canceled")
  void shouldDeliverTheRequestsWithdrawalWhenItsOfferIsCanceled() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    sharing.cancelProfileShare(authTestSupport.identityOf(sam), offer.getId());

    var withdrawn = nextChange(socket);
    assertThat(nodeOf(withdrawn).path("resolution").asString()).isEqualTo("WITHDRAWN");
    assertMatchesAFreshQuery(withdrawn, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the older request's withdrawal and then the new request when a new offer"
          + " replaces it")
  void shouldDeliverTheOlderRequestsWithdrawalAndThenTheNewRequestWhenANewOfferReplacesIt()
      throws Exception {
    var kai = kai();
    var older = steps.offer(sam, kai, riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var newer = steps.offer(sam, kai, riverside);

    var withdrawn = nextChange(socket);
    assertThat(onlyChange(withdrawn).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, older).getId().toString());
    assertThat(nodeOf(withdrawn).path("resolution").asString()).isEqualTo("WITHDRAWN");
    var made = nextChange(socket);
    assertThat(onlyChange(made).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, newer).getId().toString());
    assertMatchesAFreshQuery(made, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the end to the offering side when the Household's side ends the share")
  void shouldDeliverTheEndToTheOfferingSideWhenTheHouseholdsSideEndsTheShare() throws Exception {
    var share =
        steps.accept(
            riverside, steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, theo), riverside));
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));

    sharing.endProfileShare(authTestSupport.identityOf(riversideAdmin), share.getId());

    var ended = nextChange(socket);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, theo);
  }

  @Test
  @DisplayName("Should deliver the end to both sides when a ServerAdmin ends the share")
  void shouldDeliverTheEndToBothSidesWhenAServerAdminEndsTheShare() throws Exception {
    var share = steps.accept(riverside, steps.offer(sam, kai(), riverside));
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(sam));
    var householdSide = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    sharing.administrativelyEndProfileShare(
        authTestSupport.freshIdentityOf(serverAdmin), share.getId(), "a mistaken share");

    var toldOffering = nextChange(offeringSide);
    assertThat(nodeOf(toldOffering).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(toldOffering, sam);
    var toldHousehold = nextChange(householdSide);
    assertThat(nodeOf(toldHousehold).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(toldHousehold, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when the Account that made the offer is disabled")
  void shouldDeliverTheRequestsWithdrawalWhenTheAccountThatMadeTheOfferIsDisabled()
      throws Exception {
    steps.offer(sam, kai(), riverside);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    accountAdministration.disableAccount(
        authTestSupport.freshIdentityOf(serverAdmin), sam.account().getId());

    var withdrawn = nextChange(socket);
    assertThat(nodeOf(withdrawn).path("resolution").asString()).isEqualTo("WITHDRAWN");
    assertMatchesAFreshQuery(withdrawn, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when the manager that made the offer relinquishes"
          + " management")
  void shouldDeliverTheRequestsWithdrawalWhenTheManagerThatMadeTheOfferRelinquishesManagement()
      throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var offer = steps.offer(theo, kai, riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        profileManagement.relinquishProfileManagement(
            authTestSupport.identityOf(theo), kai.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(socket), riversideAdmin, offer);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when the manager that made the offer loses"
          + " management")
  void shouldDeliverTheRequestsWithdrawalWhenTheManagerThatMadeTheOfferLosesManagement()
      throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var offer = steps.offer(theo, kai, riverside);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        profileManagement.administrativelyRemoveProfileManager(
            authTestSupport.freshIdentityOf(serverAdmin),
            kai.getId(),
            theo.account().getId(),
            "no longer looks after Kai");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(socket), riversideAdmin, offer);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when the Account that made the offer is deleted")
  void shouldDeliverTheRequestsWithdrawalWhenTheAccountThatMadeTheOfferIsDeleted()
      throws Exception {
    var offer = steps.offer(theo, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome = accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(theo), "DELETE");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(socket), riversideAdmin, offer);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when the ServerAdmin that made the offer loses that"
          + " authority")
  void shouldDeliverTheRequestsWithdrawalWhenTheServerAdminThatMadeTheOfferLosesThatAuthority()
      throws Exception {
    var offerer = track(authTestSupport.createAdminIdentity());
    var offer = steps.offer(offerer, kai(), riverside);
    var revoker = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        accountAdministration.revokeServerAdmin(
            authTestSupport.freshIdentityOf(revoker), offerer.account().getId(), "rotation");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(socket), riversideAdmin, offer);
  }

  @Test
  @DisplayName(
      "Should deliver each visit's end, each withdrawn request and each new request when a Profile"
          + " is connected to an Account")
  void shouldDeliverEachEndWithdrawalAndNewRequestWhenAProfileIsConnectedToAnAccount()
      throws Exception {
    var brook = track(authTestSupport.createIdentity());
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    steps.accept(riverside, steps.offer(sam, ollie, riverside));
    var waiting = steps.offer(sam, ollie, brook);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(sam));
    var householdSide = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));
    var waitedOn = subscribedSocket(authTestSupport.accountBearer(brook));

    connections.connect(ollie, serverAdmin, riverside);

    var toldOffering = nextChange(offeringSide);
    assertThat(nodeOf(toldOffering).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(toldOffering, sam);
    var ended = nextChange(householdSide);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, riversideAdmin);
    var reoffered = nextChange(householdSide);
    assertThat(nodeOf(reoffered).path("__typename").asString())
        .isEqualTo("ProfileShareOfferedNotification");
    assertThat(nodeOf(reoffered).path("unread").asBoolean()).isTrue();
    assertMatchesAFreshQuery(reoffered, riversideAdmin);
    assertWithdrawn(nextChange(waitedOn), brook, waiting);
  }

  @Test
  @DisplayName(
      "Should deliver the request's resolution when an Account transfer takes the offer into the"
          + " Profile's new home")
  void shouldDeliverTheRequestsResolutionWhenAnAccountTransferTakesTheOfferIntoItsNewHome()
      throws Exception {
    var ben = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var offer = steps.offer(ben, ben.profile(), sam);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(sam));

    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferAccountCommand.builder()
                .accountId(ben.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .reason("moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var resolved = nextChange(socket);
    assertThat(onlyChange(resolved).path("notificationId").asString())
        .isEqualTo(requestFor(sam, offer).getId().toString());
    assertThat(nodeOf(resolved).path("resolution").asString()).isEqualTo("DECIDED");
    assertMatchesAFreshQuery(resolved, sam);
  }

  @Test
  @DisplayName("Should deliver the request's expiry when the sweep stores the offer's expiry")
  void shouldDeliverTheRequestsExpiryWhenTheSweepStoresTheOffersExpiry() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    steps.lapse(offer);
    expirySweeper.sweep();

    var expired = nextChange(socket);
    assertThat(onlyChange(expired).path("notificationId").asString())
        .isEqualTo(requestFor(riversideAdmin, offer).getId().toString());
    assertThat(nodeOf(expired).path("resolution").asString()).isEqualTo("EXPIRED");
    assertThat(expired.path("unreadNotificationCount").asInt()).isZero();
    assertMatchesAFreshQuery(expired, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the mark to every open socket of the Account when its recipient marks the"
          + " news read")
  void shouldDeliverTheMarkToEveryOpenSocketOfTheAccountWhenItsRecipientMarksTheNewsRead()
      throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    steps.accept(riverside, offer);
    var news = requestFor(theo, offer);
    var phone = subscribedSocket(authTestSupport.accountBearer(theo));
    var laptop = subscribedSocket(authTestSupport.accountBearer(theo));

    notificationService.markNotificationRead(authTestSupport.identityOf(theo), news.getId());

    var onPhone = nextChange(phone);
    var onLaptop = nextChange(laptop);
    assertThat(List.of(onPhone, onLaptop))
        .allSatisfy(
            marked -> {
              assertThat(onlyChange(marked).path("notificationId").asString())
                  .isEqualTo(news.getId().toString());
              assertThat(nodeOf(marked).path("__typename").asString())
                  .isEqualTo("ProfileShareAnsweredNotification");
              assertThat(nodeOf(marked).path("unread").asBoolean()).isFalse();
              assertThat(marked.path("unreadNotificationCount").asInt()).isZero();
            });
    assertMatchesAFreshQuery(onPhone, theo);
    assertMatchesAFreshQuery(onLaptop, theo);
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

  /**
   * The change resolves the recipient's request about the offer as withdrawn, as a fresh query
   * reads it.
   */
  private void assertWithdrawn(JsonNode change, TestIdentity recipient, ProfileHouseholdShare offer)
      throws Exception {
    assertThat(onlyChange(change).path("notificationId").asString())
        .isEqualTo(requestFor(recipient, offer).getId().toString());
    assertThat(nodeOf(change).path("resolution").asString()).isEqualTo("WITHDRAWN");
    assertMatchesAFreshQuery(change, recipient);
  }

  /** Each pushed edge, and the pushed unread count, are the ones a fresh query reads. */
  private void assertMatchesAFreshQuery(JsonNode change, TestIdentity reader) throws Exception {
    var response =
        mockMvc
            .perform(graphqlRequest(authTestSupport.accountBearer(reader), FRESH_QUERY))
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
