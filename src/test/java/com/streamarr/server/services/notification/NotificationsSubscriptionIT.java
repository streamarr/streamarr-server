package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlResults.firstErrorCode;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.notification.ChangedNotification;
import com.streamarr.server.domain.notification.DismissalSelector;
import com.streamarr.server.domain.notification.NotificationTell;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.RequestSettlement;
import com.streamarr.server.domain.notification.StoredNotification;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.domain.pagination.Seek;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.JooqNotificationRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.AccountAdministrationService;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.HouseholdDeletionService;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.identity.ProfileLifecycleService;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.identity.ShareRejections;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.notification.events.NotificationsChangedEvent;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import com.streamarr.server.support.ProfileConnections;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Each committed change to an Account's Notifications reaches every open socket of that Account,
 * with the edge the notifications query shows it, read from the Account's own Notifications.
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

  private static final String MARK =
      """
      mutation {
        markNotificationsRead(input: {notificationIds: [%s]}) { userErrors { __typename } }
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
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private NotificationService notificationService;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DSLContext dsl;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @Autowired private UnreadCountFailures unreadCountFailures;

  private final List<TestIdentity> identities = new ArrayList<>();
  private final List<GraphQlTransportWsTestClient> sockets = new ArrayList<>();
  private ActiveSubscriptions activeSubscriptions;
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;
  private ProfileConnections connections;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
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
    rows.deleteOf(identities.stream().map(NotificationsSubscriptionIT::accountOf).toList());
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
        .isEqualTo(request.id().toString());
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
      "Should deliver the older request's withdrawal with the new request in one change when a new"
          + " offer replaces it")
  void shouldDeliverTheOlderRequestsWithdrawalWithTheNewRequestWhenANewOfferReplacesIt()
      throws Exception {
    var kai = kai();
    var older = steps.offer(sam, kai, riverside);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var newer = steps.offer(sam, kai, riverside);

    var replaced = nextChange(socket);
    assertThat(
            replaced
                .path("notifications")
                .valueStream()
                .map(
                    change ->
                        tuple(
                            change.path("notificationId").asString(),
                            textOrNull(change.path("edge").path("node").path("resolution")))))
        .containsExactly(
            tuple(requestFor(riversideAdmin, older).id().toString(), "WITHDRAWN"),
            tuple(requestFor(riversideAdmin, newer).id().toString(), null));
    assertMatchesAFreshQuery(replaced, riversideAdmin);
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
  @DisplayName(
      "Should deliver the end to the Household's side when the offering side ends the share")
  void shouldDeliverTheEndToTheHouseholdsSideWhenTheOfferingSideEndsTheShare() throws Exception {
    var share = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    sharing.endProfileShare(authTestSupport.identityOf(sam), share.getId());

    var ended = nextChange(socket);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, riversideAdmin);
  }

  @Test
  @DisplayName(
      "Should deliver the end to the Household an Account leaves when its transfer ends the share"
          + " into it")
  void shouldDeliverTheEndToTheHouseholdAnAccountLeavesWhenItsTransferEndsTheShareIntoIt()
      throws Exception {
    var ben = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

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
    var ended = nextChange(socket);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, riversideAdmin);
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
      "Should deliver the request's withdrawal and the end of the share into the Household the"
          + " Profile leaves when the Profile is transferred")
  void shouldDeliverTheWithdrawalAndTheEndOfTheShareItLeavesWhenTheProfileIsTransferred()
      throws Exception {
    var kai = kai();
    var offer = steps.offer(sam, kai, riverside);
    var brook = track(authTestSupport.createIdentity());
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var householdLeft = subscribedSocket(authTestSupport.accountBearer(sam));
    var householdOffered = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        profileLifecycle.transferProfile(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferProfileCommand.builder()
                .profileId(kai.getId())
                .destinationHouseholdId(brook.household().getId())
                .localManagerAccountId(brook.account().getId())
                .reason("Kai lives with Brook now")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(householdOffered), riversideAdmin, offer);
    var ended = nextChange(householdLeft);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, sam);
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
    var endedAndReoffered = nextChange(householdSide);
    assertThat(
            endedAndReoffered
                .path("notifications")
                .valueStream()
                .map(change -> change.path("edge").path("node")))
        .extracting(
            node -> node.path("__typename").asString(), node -> node.path("unread").asBoolean())
        .containsExactlyInAnyOrder(
            tuple("ProfileShareEndedNotification", true),
            tuple("ProfileShareOfferedNotification", true));
    assertMatchesAFreshQuery(endedAndReoffered, riversideAdmin);
    assertWithdrawn(nextChange(waitedOn), brook, waiting);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when an Account transfer takes the offer into the"
          + " Profile's new home")
  void shouldDeliverTheRequestsWithdrawalWhenAnAccountTransferTakesTheOfferIntoItsNewHome()
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
    assertWithdrawn(nextChange(socket), sam, offer);
  }

  @Test
  @DisplayName(
      "Should deliver the request's withdrawal when deleting a Household moves its final Account"
          + " into the Household its Profile was offered to")
  void shouldDeliverTheRequestsWithdrawalWhenDeletingAHouseholdMovesItsFinalAccountIntoTheOffer()
      throws Exception {
    var brook = track(authTestSupport.createIdentity());
    var offer = steps.offer(brook, brook.profile(), sam);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(sam));

    var outcome =
        householdDeletion.transferLastAccountAndDeleteHousehold(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferLastAccountAndDeleteHouseholdCommand.builder()
                .householdId(brook.household().getId())
                .destinationHouseholdId(sam.household().getId())
                .reason("Brook moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertWithdrawn(nextChange(socket), sam, offer);
  }

  @Test
  @DisplayName(
      "Should deliver one change to each recipient, with null edges for only its own Notifications,"
          + " when deleting a Profile dismisses what was said about it and its shares")
  void shouldDeliverOneChangeToEachRecipientWhenDeletingAProfileDismissesWhatWasSaidAboutIt()
      throws Exception {
    var kai = kai();
    var ended = steps.accept(riverside, steps.offer(sam, kai, riverside));
    sharing.endProfileShare(authTestSupport.identityOf(riversideAdmin), ended.getId());
    steps.offer(sam, kai, riverside);
    var samsLost = idsOf(sam);
    var adminsLost = idsOf(riversideAdmin);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(sam));
    var householdSide = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        profileLifecycle.administrativelyDeleteProfile(
            authTestSupport.freshIdentityOf(serverAdmin), kai.getId(), "a duplicate");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertDismissed(nextChange(offeringSide), samsLost);
    assertDismissed(nextChange(householdSide), adminsLost);
    var sentinel = tellSentinel(sam, riversideAdmin);
    assertThat(onlyChange(nextChange(offeringSide)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(sam)).toString());
    assertThat(onlyChange(nextChange(householdSide)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(riversideAdmin)).toString());
  }

  @Test
  @DisplayName(
      "Should push no Share ended news, only the dismissal of what the offering side was told, when"
          + " deleting a Household ends its shares")
  void shouldPushNoShareEndedNewsWhenDeletingAHouseholdEndsItsShares() throws Exception {
    var lakeside = track(authTestSupport.createIdentity());
    var share = steps.accept(lakeside, steps.offer(sam, kai(), lakeside));
    var told = requestFor(sam, share).id().toString();
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(sam));

    var outcome =
        householdDeletion.transferLastAccountAndDeleteHousehold(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferLastAccountAndDeleteHouseholdCommand.builder()
                .householdId(lakeside.household().getId())
                .destinationHouseholdId(serverAdmin.household().getId())
                .reason("closing")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertDismissed(nextChange(offeringSide), List.of(told));
    var sentinel = tellSentinel(sam);
    assertThat(onlyChange(nextChange(offeringSide)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(sam)).toString());
  }

  @Test
  @DisplayName(
      "Should push no Share ended news, only the dismissal of what each side was told, when"
          + " deleting a Profile ends its shares")
  void shouldPushNoShareEndedNewsWhenDeletingAProfileEndsItsShares() throws Exception {
    var kai = kai();
    var share = steps.accept(riverside, steps.offer(sam, kai, riverside));
    var toldOfferingSide = requestFor(sam, share).id().toString();
    var toldHouseholdSide = requestFor(riversideAdmin, share).id().toString();
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offeringSide = subscribedSocket(authTestSupport.accountBearer(sam));
    var householdSide = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        profileLifecycle.administrativelyDeleteProfile(
            authTestSupport.freshIdentityOf(serverAdmin), kai.getId(), "a duplicate");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertDismissed(nextChange(offeringSide), List.of(toldOfferingSide));
    assertDismissed(nextChange(householdSide), List.of(toldHouseholdSide));
    var sentinel = tellSentinel(sam, riversideAdmin);
    assertThat(onlyChange(nextChange(offeringSide)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(sam)).toString());
    assertThat(onlyChange(nextChange(householdSide)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(riversideAdmin)).toString());
  }

  @Test
  @DisplayName(
      "Should deliver one Share ended news to an Account on both sides when a ServerAdmin ends the"
          + " share")
  void shouldDeliverOneShareEndedNewsToAnAccountOnBothSidesWhenAServerAdminEndsTheShare()
      throws Exception {
    var ollie = steps.profileManagedBy("Ollie", sam, sam, riversideAdmin);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var bothSides = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    sharing.administrativelyEndProfileShare(
        authTestSupport.freshIdentityOf(serverAdmin), share.getId(), "a mistaken share");

    var ended = nextChange(bothSides);
    assertThat(nodeOf(ended).path("__typename").asString())
        .isEqualTo("ProfileShareEndedNotification");
    assertMatchesAFreshQuery(ended, riversideAdmin);
    var sentinel = tellSentinel(riversideAdmin);
    assertThat(onlyChange(nextChange(bothSides)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(riversideAdmin)).toString());
  }

  @Test
  @DisplayName(
      "Should deliver the dismissal of its waiting request when a HouseholdAdmin is demoted")
  void shouldDeliverTheDismissalWhenAHouseholdAdminIsDemoted() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    var request = requestFor(riversideAdmin, offer);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        accountAdministration.revokeHouseholdAdmin(
            authTestSupport.freshIdentityOf(serverAdmin), riversideAdmin.account().getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var dismissed = nextChange(socket);
    assertThat(onlyChange(dismissed).path("notificationId").asString())
        .isEqualTo(request.id().toString());
    assertThat(onlyChange(dismissed).path("edge").isNull()).isTrue();
    assertThat(dismissed.path("unreadNotificationCount").asInt()).isZero();
    assertThat(requestFor(riverside, offer).resolution()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should deliver the dismissal of its waiting request when a HouseholdAdmin's Account moves to"
          + " another Household")
  void shouldDeliverTheDismissalWhenAHouseholdAdminsAccountMovesToAnotherHousehold()
      throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    var request = requestFor(riversideAdmin, offer);
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferAccountCommand.builder()
                .accountId(riversideAdmin.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                .reason("moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var dismissed = nextChange(socket);
    assertThat(onlyChange(dismissed).path("notificationId").asString())
        .isEqualTo(request.id().toString());
    assertThat(onlyChange(dismissed).path("edge").isNull()).isTrue();
    assertThat(dismissed.path("unreadNotificationCount").asInt()).isZero();
    assertThat(requestFor(riverside, offer).resolution()).isEmpty();
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

    notificationService.markNotificationsRead(authTestSupport.identityOf(theo), List.of(news.id()));

    var onPhone = nextChange(phone);
    var onLaptop = nextChange(laptop);
    assertThat(List.of(onPhone, onLaptop))
        .allSatisfy(
            marked -> {
              assertThat(onlyChange(marked).path("notificationId").asString())
                  .isEqualTo(news.id().toString());
              assertThat(nodeOf(marked).path("__typename").asString())
                  .isEqualTo("ProfileShareAnsweredNotification");
              assertThat(nodeOf(marked).path("unread").asBoolean()).isFalse();
              assertThat(marked.path("unreadNotificationCount").asInt()).isZero();
            });
    assertMatchesAFreshQuery(onPhone, theo);
    assertMatchesAFreshQuery(onLaptop, theo);
  }

  @Test
  @DisplayName(
      "Should deliver one change with every Notification marked when its recipient marks several"
          + " read")
  void shouldDeliverOneChangeWithEveryNotificationMarkedWhenItsRecipientMarksSeveralRead()
      throws Exception {
    var kai = newsFor(theo, "Kai");
    var ollie = newsFor(theo, "Ollie");
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));

    markRead(theo, kai, ollie);

    var marked = nextChange(socket);
    assertThat(
            marked
                .path("notifications")
                .valueStream()
                .map(change -> change.path("notificationId").asString()))
        .containsExactlyInAnyOrder(kai.id().toString(), ollie.id().toString());
    assertThat(marked.path("unreadNotificationCount").asInt()).isZero();
    assertMatchesAFreshQuery(marked, theo);
    var sentinel = newsFor(theo, "Ash");
    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(sentinel.id().toString());
  }

  @Test
  @DisplayName("Should deliver nothing when no Notification among the ids becomes read")
  void shouldDeliverNothingWhenNoNotificationAmongTheIdsBecomesRead() throws Exception {
    var kai = newsFor(theo, "Kai");
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));
    markRead(theo, kai);
    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(kai.id().toString());

    markRead(
        theo, kai, requestFor(sam, shareRepository.findById(kai.subject().id()).orElseThrow()));
    var sentinel = newsFor(theo, "Ash");

    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(sentinel.id().toString());
  }

  @Test
  @DisplayName("Should deliver nothing when the recipient marks a request that was decided")
  void shouldDeliverNothingWhenTheRecipientMarksARequestThatWasDecided() throws Exception {
    var offer = steps.offer(sam, kai(), riverside);
    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());
    var decided = requestFor(riversideAdmin, offer);
    var socket = subscribedSocket(authTestSupport.accountBearer(riversideAdmin));

    markRead(riversideAdmin, decided);
    var sentinel = tellSentinel(riversideAdmin);

    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(sentinel.get(accountOf(riversideAdmin)).toString());
  }

  @Test
  @DisplayName("Should deliver nothing and leave the news unread when the mark rolls back")
  void shouldDeliverNothingAndLeaveTheNewsUnreadWhenTheMarkRollsBack() {
    var kai = newsFor(theo, "Kai");
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));

    transactionTemplate.executeWithoutResult(
        status -> {
          notificationService.markNotificationsRead(
              authTestSupport.identityOf(theo), List.of(kai.id()));
          status.setRollbackOnly();
        });
    var sentinel = newsFor(theo, "Ash");

    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(sentinel.id().toString());
    assertThat(stored(kai).readAt()).isEmpty();
  }

  @Test
  @DisplayName("Should deliver nothing when a change is announced with no transaction behind it")
  void shouldDeliverNothingWhenAChangeIsAnnouncedWithNoTransactionBehindIt() {
    var kai = newsFor(theo, "Kai");
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));

    eventPublisher.publishEvent(
        new NotificationsChangedEvent(List.of(new ChangedNotification(kai.id(), accountOf(theo)))));
    var sentinel = newsFor(theo, "Ash");

    assertThat(onlyChange(nextChange(socket)).path("notificationId").asString())
        .isEqualTo(sentinel.id().toString());
  }

  @Test
  @DisplayName(
      "Should null the unread count with an error at its path, and deliver the next change on the"
          + " same operation, when the count cannot be read")
  void shouldNullTheUnreadCountAndDeliverTheNextChangeWhenTheCountCannotBeRead() {
    var socket = subscribedSocket(authTestSupport.accountBearer(theo));
    unreadCountFailures.failTheNextCount();

    var first = tellSentinel(theo).get(accountOf(theo));
    var failed = socket.nextMessageFor(OPERATION);
    var second = tellSentinel(theo).get(accountOf(theo));
    var delivered = nextChange(socket);

    assertThat(failed.path("type").asString()).isEqualTo("next");
    assertThat(failed.path("payload").path("errors").valueStream())
        .singleElement()
        .satisfies(
            error ->
                assertThat(error.path("path").valueStream().map(JsonNode::asString))
                    .containsExactly(FIELD, "unreadNotificationCount"));
    var withoutCount = failed.path("payload").path("data").path(FIELD);
    assertThat(withoutCount.path("unreadNotificationCount").isNull()).isTrue();
    assertThat(onlyChange(withoutCount).path("notificationId").asString())
        .isEqualTo(first.toString());
    assertThat(onlyChange(withoutCount).path("edge").isNull()).isFalse();
    assertThat(onlyChange(delivered).path("notificationId").asString())
        .isEqualTo(second.toString());
    assertThat(delivered.path("unreadNotificationCount").asInt()).isEqualTo(2);
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

  /** The recipient's news that Riverside accepted an offer of a new Profile that Theo manages. */
  private Row newsFor(TestIdentity recipient, String profileName) {
    var offer = steps.offer(sam, steps.profileManagedBy(profileName, sam, sam, theo), riverside);
    return requestFor(recipient, steps.accept(riverside, offer));
  }

  private void markRead(TestIdentity reader, Row... notifications) throws Exception {
    var ids =
        Stream.of(notifications)
            .map(notification -> "\"%s\"".formatted(notification.id()))
            .collect(Collectors.joining(", "));
    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(reader), MARK.formatted(ids)))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());
  }

  /** The recipient's Notification about the share. */
  private Row requestFor(TestIdentity recipient, ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(notification -> notification.recipient().equals(accountOf(recipient)))
        .findFirst()
        .orElseThrow();
  }

  private Row stored(Row notification) {
    return rows.of(notification.recipient()).stream()
        .filter(row -> row.id().equals(notification.id()))
        .findFirst()
        .orElseThrow();
  }

  private List<String> idsOf(TestIdentity recipient) {
    return rows.of(accountOf(recipient)).stream().map(row -> row.id().toString()).toList();
  }

  /** The change names exactly the Notifications, once each, each with a null edge. */
  private static void assertDismissed(JsonNode change, List<String> notificationIds) {
    assertThat(change.path("notifications").valueStream())
        .allSatisfy(notification -> assertThat(notification.path("edge").isNull()).isTrue())
        .extracting(notification -> notification.path("notificationId").asString())
        .containsExactlyInAnyOrderElementsOf(notificationIds);
    assertThat(change.path("unreadNotificationCount").asInt()).isZero();
  }

  /** Tells each recipient news about a new share, and answers each one's Notification. */
  private Map<RecipientReference, UUID> tellSentinel(TestIdentity... recipients) {
    var subject = SubjectReference.profileShare(UUID.randomUUID());
    publish(
        TellNotificationEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .subject(subject)
            .recipients(
                Stream.of(recipients)
                    .map(NotificationsSubscriptionIT::accountOf)
                    .collect(Collectors.toSet()))
            .snapshot(new ProfileShareAnsweredSnapshot("Ash", ProfileShareAnswer.ACCEPTED))
            .at(Instant.now())
            .build());
    return rows.about(subject).stream().collect(Collectors.toMap(Row::recipient, Row::id));
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  /**
   * The change resolves the recipient's request about the offer as withdrawn, as a fresh query
   * reads it.
   */
  private void assertWithdrawn(JsonNode change, TestIdentity recipient, ProfileHouseholdShare offer)
      throws Exception {
    assertThat(onlyChange(change).path("notificationId").asString())
        .isEqualTo(requestFor(recipient, offer).id().toString());
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

  private static String textOrNull(JsonNode value) {
    if (value.isNull() || value.isMissingNode()) {
      return null;
    }

    return value.asString();
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

  @TestConfiguration(proxyBeanMethods = false)
  static class UnreadCountFailureConfiguration {

    @Bean
    @Primary
    UnreadCountFailures unreadCountFailures(JooqNotificationRepository stored) {
      return new UnreadCountFailures(stored);
    }
  }

  /** The module's table, whose unread count a test can make fail once. */
  @RequiredArgsConstructor
  static final class UnreadCountFailures implements NotificationRepository {

    private final NotificationRepository stored;
    private final AtomicBoolean failNextCount = new AtomicBoolean();

    void failTheNextCount() {
      failNextCount.set(true);
    }

    @Override
    public int countUnread(RecipientReference recipient, Instant now) {
      if (failNextCount.compareAndSet(true, false)) {
        throw new DataAccessResourceFailureException("The unread count could not be read");
      }

      return stored.countUnread(recipient, now);
    }

    @Override
    public List<ChangedNotification> tellNews(NotificationTell tell) {
      return stored.tellNews(tell);
    }

    @Override
    public List<ChangedNotification> tellRequests(NotificationTell tell) {
      return stored.tellRequests(tell);
    }

    @Override
    public List<ChangedNotification> resolve(RequestSettlement resolution) {
      return stored.resolve(resolution);
    }

    @Override
    public List<ChangedNotification> dismiss(DismissalSelector selector) {
      return stored.dismiss(selector);
    }

    @Override
    public void deleteOfRecipient(RecipientReference recipient) {
      stored.deleteOfRecipient(recipient);
    }

    @Override
    public int deleteHistoryBefore(Instant cutoff, Instant now, int limit) {
      return stored.deleteHistoryBefore(cutoff, now, limit);
    }

    // The tests' stored rows take the name Row.
    @Override
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    public List<com.streamarr.server.domain.pagination.Row<StoredNotification>> rowsOfRecipient(
        RecipientReference recipient, Seek seek) {
      return stored.rowsOfRecipient(recipient, seek);
    }

    @Override
    @SuppressWarnings("checkstyle:fullyQualifiedName")
    public List<com.streamarr.server.domain.pagination.Row<StoredNotification>>
        rowsOfRecipientWithIds(RecipientReference recipient, Collection<UUID> notificationIds) {
      return stored.rowsOfRecipientWithIds(recipient, notificationIds);
    }

    @Override
    public List<ChangedNotification> markRead(
        RecipientReference recipient, Collection<UUID> notificationIds, Instant now) {
      return stored.markRead(recipient, notificationIds, now);
    }
  }
}
