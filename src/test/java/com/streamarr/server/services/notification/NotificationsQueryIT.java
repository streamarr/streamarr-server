package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.DismissNotificationsEvent;
import com.streamarr.server.services.events.notification.DismissalSelector;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.AccountAdministrationService;
import com.streamarr.server.services.identity.AccountLifecycleService;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Notifications Query Integration Tests")
class NotificationsQueryIT extends AbstractIntegrationTest {

  // An offer's answer is nullable and an answer's is not, so each takes its own name.
  private static final String NOTIFICATIONS =
      """
      query {
        unreadNotificationCount
        notifications(first: 10) {
          edges {
            cursor
            orderKey
            node {
              __typename id message createdAt latestEventAt unread
              ... on RequestNotification { expiresAt resolution resolvedAt }
              ... on ProfileShareOfferedNotification { profileName shareId offerAnswer: answer }
              ... on ProfileShareAnsweredNotification { profileName shareId answer }
              ... on ProfileShareEndedNotification { profileName shareId endedBy }
            }
          }
          pageInfo { hasNextPage endCursor }
        }
      }
      """;

  private static final String HOUSEHOLD_SHARES =
      """
      query { householdShares(householdId: "%s") { edges { node { id } } } }
      """;

  private static final String PAGE =
      """
      query { notifications(first: %d%s) {
        edges { cursor node { id } } pageInfo { hasNextPage } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
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
    rows.deleteOf(identities.stream().map(NotificationsQueryIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should list a waiting request with its share's id, the Profile's name and its line when a"
          + " Profile is offered to the reader's Household")
  void
      shouldListAWaitingRequestWithItsShareIdNameAndLineWhenAProfileIsOfferedToTheReadersHousehold()
          throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var offer = steps.offer(sam, kai, riverside);
    // The database keeps microseconds; a clock with finer ticks gives the offer in memory more.
    var storedExpiry = shareRepository.findById(offer.getId()).orElseThrow().getExpiresAt();

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.__typename")
                .value("ProfileShareOfferedNotification"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("“Kai” was offered to your Household."))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value("Kai"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.shareId").value(offer.getId().toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(true))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolvedAt").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges[0].node.offerAnswer").doesNotExist())
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.expiresAt").value(storedExpiry.toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].orderKey").isString())
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(false));
  }

  @Test
  @DisplayName(
      "Should read the request's resolution, its time and the answer once the offer is decided")
  void shouldReadTheRequestsResolutionTimeAndAnswerOnceItIsDecided() throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var offer = steps.accept(riverside, steps.offer(sam, kai, riverside));
    var decidedAt = shareRepository.findById(offer.getId()).orElseThrow().getDecidedAt();

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(false))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("DECIDED"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.resolvedAt").value(decidedAt.toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.offerAnswer").value("ACCEPTED"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("The offer of “Kai” to your Household was accepted."));
  }

  @Test
  @DisplayName("Should keep the name it was told when the Profile is renamed after the event")
  void shouldKeepTheNameItWasToldWhenTheProfileIsRenamedAfterTheEvent() throws Exception {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    steps.accept(riverside, steps.offer(sam, kai, riverside));
    rename(kai, "Kai Renamed");

    assertToldOf(riversideAdmin, "Kai");
    assertToldOf(theo, "Kai");
  }

  @Test
  @DisplayName(
      "Should read the resolved request as read under the name it was told when its offer was"
          + " rejected and the Profile was renamed")
  void shouldReadTheResolvedRequestAsReadUnderTheNameItWasToldWhenItsOfferWasRejected()
      throws Exception {
    var kai = steps.profileManagedBy("Kai", sam, sam);
    var offer = steps.offer(sam, kai, riverside);
    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());
    rename(kai, "Kai Renamed");

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(false))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("DECIDED"))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.offerAnswer").value("REJECTED"))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value("Kai"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("The offer of “Kai” to your Household was declined."));
  }

  @Test
  @DisplayName(
      "Should give the time of the first event and the time of the latest when news is told again")
  void shouldGiveTheTimeOfTheFirstEventAndTheTimeOfTheLatestWhenNewsIsToldAgain() throws Exception {
    var subject = aShare();
    var firstEvent = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);
    var latestEvent = firstEvent.plus(Duration.ofMinutes(1));
    var news = tellNews(riversideAdmin, subject, firstEvent);
    tellNews(riversideAdmin, subject, latestEvent);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.id").value(news.toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.createdAt").value(firstEvent.toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.latestEventAt")
                .value(latestEvent.toString()));
  }

  @Test
  @DisplayName(
      "Should give a newer Notification an order key that sorts before an older one's, and order"
          + " equal times by id")
  void shouldGiveANewerNotificationAnOrderKeyThatSortsBeforeAnOlderOnesAndOrderEqualTimesById()
      throws Exception {
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var later = Instant.now().plus(Duration.ofMinutes(1));
    tellNews(riversideAdmin, aShare(), later);
    tellNews(riversideAdmin, aShare(), later.plus(Duration.ofMinutes(1)));
    tellNews(riversideAdmin, aShare(), later.plus(Duration.ofMinutes(1)));

    var page =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(riversideAdmin),
                    "query { notifications(first: 10) { edges { orderKey } } }"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.notifications.edges", hasSize(4)))
            .andReturn();
    List<String> keys =
        JsonPath.read(
            page.getResponse().getContentAsString(), "$.data.notifications.edges[*].orderKey");

    assertThat(keys).doesNotHaveDuplicates().isSortedAccordingTo(Comparator.naturalOrder());
  }

  @Test
  @DisplayName(
      "Should return twenty Notifications and say more follow when first is not given and the"
          + " Account has twenty-one")
  void shouldReturnTwentyNotificationsAndSayMoreFollowWhenFirstIsNotGivenAndTheAccountHasMore()
      throws Exception {
    for (var made = 1; made <= 21; made++) {
      tellNews(riversideAdmin, aShare(), Instant.now().plus(Duration.ofMinutes(made)));
    }

    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(riversideAdmin),
                "query { notifications { edges { cursor } pageInfo { hasNextPage } } }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(20)))
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(true));
  }

  @Test
  @DisplayName("Should refuse the list and the count when the session is device-bound")
  void shouldRefuseTheListAndTheCountWhenTheSessionIsDeviceBound() throws Exception {
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var bearer = authTestSupport.deviceBoundProfileBearer(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, "query { notifications { edges { cursor } } }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(graphqlRequest(bearer, "query { unreadNotificationCount }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName(
      "Should continue after the previous page's last Notification when its cursor is the after"
          + " argument")
  void shouldContinueAfterThePreviousPagesLastNotificationWhenItsCursorIsTheAfterArgument()
      throws Exception {
    var older = tellNews(riversideAdmin, aShare(), Instant.now());
    var newer = tellNews(riversideAdmin, aShare(), Instant.now().plus(Duration.ofMinutes(1)));
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    var firstPage =
        mockMvc
            .perform(graphqlRequest(bearer, PAGE.formatted(1, "")))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.notifications.edges[0].node.id").value(newer.toString()))
            .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(true))
            .andReturn();
    String cursor =
        JsonPath.read(
            firstPage.getResponse().getContentAsString(), "$.data.notifications.edges[0].cursor");

    mockMvc
        .perform(graphqlRequest(bearer, PAGE.formatted(1, after(cursor))))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges[0].node.id").value(older.toString()))
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(false));
  }

  @Test
  @DisplayName(
      "Should start after the cursor's place when the Notification it named has since moved to the"
          + " top")
  void shouldStartAfterTheCursorsPlaceWhenTheNotificationItNamedMovedToTheTop() throws Exception {
    var oldest = tellNews(riversideAdmin, aShare(), Instant.now());
    var named = aShare();
    tellNews(riversideAdmin, named, Instant.now().plus(Duration.ofMinutes(1)));
    var newest = tellNews(riversideAdmin, aShare(), Instant.now().plus(Duration.ofMinutes(2)));
    var cursor = cursorAfterFirst(2);
    var moved = tellNews(riversideAdmin, named, Instant.now().plus(Duration.ofMinutes(3)));

    assertPageAfter(cursor, oldest);
    assertThat(firstPageIds(10)).containsExactly(moved, newest, oldest);
  }

  @Test
  @DisplayName(
      "Should start after the cursor's place when the Notification it named has since been"
          + " dismissed")
  void shouldStartAfterTheCursorsPlaceWhenTheNotificationItNamedWasDismissed() throws Exception {
    var oldest = tellNews(riversideAdmin, aShare(), Instant.now());
    var named = tellNews(riversideAdmin, aShare(), Instant.now().plus(Duration.ofMinutes(1)));
    var newest = tellNews(riversideAdmin, aShare(), Instant.now().plus(Duration.ofMinutes(2)));
    var cursor = cursorAfterFirst(2);
    publish(new DismissNotificationsEvent(DismissalSelector.byId(Set.of(named)).build()));

    assertPageAfter(cursor, oldest);
    assertThat(firstPageIds(10)).containsExactly(newest, oldest);
  }

  @Test
  @DisplayName(
      "Should refuse the page as INVALID_CURSOR when the cursor is not one the list gave or is"
          + " another Account's")
  void shouldRefuseThePageAsInvalidCursorWhenTheCursorIsNotOneTheListGaveOrIsAnotherAccounts()
      throws Exception {
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var others =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(riverside),
                    "query { notifications(first: 1) { edges { cursor } } }"))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
    String othersCursor =
        JsonPath.read(
            others.getResponse().getContentAsString(), "$.data.notifications.edges[0].cursor");
    var bearer = authTestSupport.accountBearer(riversideAdmin);

    for (var cursor : List.of("not-a-cursor", othersCursor)) {
      mockMvc
          .perform(
              graphqlRequest(
                  bearer,
                  "query { notifications(first: 1, after: \"%s\") { edges { cursor } } }"
                      .formatted(cursor)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
    }
  }

  @Test
  @DisplayName(
      "Should keep giving the Household's side its end news and its resolved request, with the"
          + " share's id and the Profile's name, while the share queries no longer show the share,"
          + " when a ProfileManager ends the share")
  void shouldKeepGivingTheHouseholdsSideItsNotificationsWhenAProfileManagerEndsTheShare()
      throws Exception {
    var rowan = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var share =
        steps.accept(
            riverside, steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, rowan), riverside));
    assertThat(sharing.endProfileShare(authTestSupport.identityOf(rowan), share.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    var bearer = authTestSupport.accountBearer(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.__typename",
                contains("ProfileShareEndedNotification", "ProfileShareOfferedNotification")))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.endedBy").value("OFFERING_SIDE"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("“Kai” is no longer shared with your Household."))
        .andExpect(jsonPath("$.data.notifications.edges[1].node.resolution").value("DECIDED"))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.shareId",
                contains(share.getId().toString(), share.getId().toString())))
        .andExpect(
            jsonPath("$.data.notifications.edges[*].node.profileName", contains("Kai", "Kai")));
    mockMvc
        .perform(
            graphqlRequest(
                bearer,
                "query { householdShares(householdId: \"%s\") { edges { node { id } } } }"
                    .formatted(riverside.household().getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath(
                "$.data.householdShares.edges[*].node.id", not(hasItem(share.getId().toString()))));
  }

  @Test
  @DisplayName(
      "Should leave the waiting requests out of the list and the count when their recipient is"
          + " demoted")
  void shouldLeaveTheWaitingRequestsOutOfTheListAndCountWhenItsRecipientIsDemoted()
      throws Exception {
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    steps.offer(sam, steps.profileManagedBy("Ollie", sam, sam), riverside);

    demote(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(0)));
    assertThat(rows.of(accountOf(riverside))).hasSize(2);
  }

  @Test
  @DisplayName(
      "Should keep the resolved requests and the news when a HouseholdAdmin is demoted, and only"
          + " the waiting request leaves")
  void shouldKeepTheResolvedRequestsAndNewsWhenAHouseholdAdminIsDemoted() throws Exception {
    var ended = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));
    sharing.endProfileShare(authTestSupport.identityOf(sam), ended.getId());
    var rejected = steps.offer(sam, steps.profileManagedBy("Ollie", sam, sam), riverside);
    sharing.rejectProfileShare(authTestSupport.identityOf(riverside), rejected.getId());
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);

    demote(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.__typename",
                contains(
                    "ProfileShareOfferedNotification",
                    "ProfileShareEndedNotification",
                    "ProfileShareOfferedNotification")))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.resolution", contains("DECIDED", "DECIDED")))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.shareId",
                contains(
                    rejected.getId().toString(),
                    ended.getId().toString(),
                    ended.getId().toString())));
  }

  /**
   * The record's share query for the same share is a by-id read that is not built yet, and a
   * HouseholdAdmin never lists an ended share, so the Household's shares stand in for it: the
   * demoted Account, which listed them, now reads none.
   */
  @Test
  @DisplayName(
      "Should keep the share ended news and the name it was told after its recipient is demoted,"
          + " while the Household's shares answer none")
  void
      shouldKeepTheShareEndedNewsAndItsNameAfterItsRecipientIsDemotedWhileTheShareQueryAnswersNone()
          throws Exception {
    var told = sam.profile().getName();
    var ended = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));
    sharing.endProfileShare(authTestSupport.identityOf(sam), ended.getId());
    assertThat(householdShareIds(riversideAdmin)).isNotEmpty();

    demote(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.__typename")
                .value("ProfileShareEndedNotification"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.shareId").value(ended.getId().toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value(told))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(true))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.message", containsString(told)));
    assertThat(householdShareIds(riversideAdmin)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give no request back when a demoted HouseholdAdmin is promoted again while the offer"
          + " waits")
  void shouldGiveNoRequestBackWhenADemotedHouseholdAdminIsPromotedAgain() throws Exception {
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    demote(riversideAdmin);

    steps.promoted(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(0)));
  }

  @Test
  @DisplayName(
      "Should leave the waiting requests out when their recipient's Account moves to another"
          + " Household, and keep the other HouseholdAdmins' requests")
  void shouldLeaveTheWaitingRequestsOutWhenTheirRecipientsAccountMovesToAnotherHousehold()
      throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);

    moveToSamsHousehold(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(0)));
    assertThat(rows.about(SubjectReference.profileShare(offer.getId())))
        .extracting(StoredNotificationRows.Row::recipient)
        .containsExactly(accountOf(riverside));
  }

  @Test
  @DisplayName(
      "Should keep its news and its resolved requests when a HouseholdAdmin's Account moves to"
          + " another Household")
  void shouldKeepItsNewsAndResolvedRequestsWhenAHouseholdAdminMoves() throws Exception {
    var ended = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));
    sharing.endProfileShare(authTestSupport.identityOf(sam), ended.getId());
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);

    moveToSamsHousehold(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.__typename",
                contains("ProfileShareEndedNotification", "ProfileShareOfferedNotification")))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.shareId",
                contains(ended.getId().toString(), ended.getId().toString())));
  }

  /**
   * The ids of the shares into Riverside that the reader lists, signed in afresh, so its token
   * carries the role that its Account holds now.
   */
  private List<String> householdShareIds(TestIdentity reader) throws Exception {
    var signedInAfresh =
        TestIdentity.builder()
            .account(userAccountRepository.findById(reader.account().getId()).orElseThrow())
            .household(reader.household())
            .profile(reader.profile())
            .session(reader.session())
            .rawRefreshToken(reader.rawRefreshToken())
            .build();
    var body =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(signedInAfresh),
                    HOUSEHOLD_SHARES.formatted(riverside.household().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.data.householdShares.edges[*].node.id");
  }

  /** A ServerAdmin moves the Account into Sam's Household, where it keeps visiting its old one. */
  private void moveToSamsHousehold(TestIdentity account) {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    assertThat(
            accountLifecycle.transferAccount(
                authTestSupport.freshIdentityOf(serverAdmin),
                TransferAccountCommand.builder()
                    .accountId(account.account().getId())
                    .destinationHouseholdId(sam.household().getId())
                    .sourceHouseholdAccess(SourceHouseholdAccess.KEEP_AS_VISITOR)
                    .reason("moved in with Sam")
                    .build()))
        .isInstanceOf(Outcome.Accepted.class);
  }

  /** A ServerAdmin takes the Account's HouseholdAdmin role away. */
  private void demote(TestIdentity householdAdmin) {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    assertThat(
            accountAdministration.revokeHouseholdAdmin(
                authTestSupport.freshIdentityOf(serverAdmin), householdAdmin.account().getId()))
        .isInstanceOf(Outcome.Accepted.class);
  }

  /** The reader's one Notification names the Profile, in its line too, by the name. */
  private void assertToldOf(TestIdentity reader, String profileName) throws Exception {
    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(reader), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value(profileName))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[0].node.message",
                containsString("“%s”".formatted(profileName))));
  }

  /** The cursor of the last of the first page of the given size. */
  private String cursorAfterFirst(int size) throws Exception {
    var page =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(riversideAdmin), PAGE.formatted(size, "")))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.notifications.edges", hasSize(size)))
            .andReturn();
    return JsonPath.read(
        page.getResponse().getContentAsString(),
        "$.data.notifications.edges[%d].cursor".formatted(size - 1));
  }

  private void assertPageAfter(String cursor, UUID... ids) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(riversideAdmin), PAGE.formatted(10, after(cursor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.id",
                contains(Arrays.stream(ids).map(UUID::toString).toArray())));
  }

  private List<UUID> firstPageIds(int size) throws Exception {
    var page =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(riversideAdmin), PAGE.formatted(size, "")))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn();
    List<String> ids =
        JsonPath.read(
            page.getResponse().getContentAsString(), "$.data.notifications.edges[*].node.id");
    return ids.stream().map(UUID::fromString).toList();
  }

  private static String after(String cursor) {
    return ", after: \"%s\"".formatted(cursor);
  }

  /**
   * News to the recipient that an offer about the subject was accepted, told as a domain tells it,
   * and the id of the recipient's news about the subject.
   */
  private UUID tellNews(TestIdentity recipient, SubjectReference subject, Instant at) {
    publish(
        TellNotificationEvent.builder()
            .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
            .subject(subject)
            .recipients(Set.of(accountOf(recipient)))
            .snapshot(new ProfileShareAnsweredSnapshot("Kai", ProfileShareAnswer.ACCEPTED))
            .at(at)
            .build());
    return rows.about(subject).stream()
        .filter(row -> row.recipient().equals(accountOf(recipient)))
        .reduce((_, last) -> last)
        .orElseThrow()
        .id();
  }

  private void publish(Object event) {
    transactionTemplate.executeWithoutResult(_ -> eventPublisher.publishEvent(event));
  }

  // A share the module never looks up: news about it is all a list needs.
  private static SubjectReference aShare() {
    return SubjectReference.profileShare(UUID.randomUUID());
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private void rename(Profile profile, String name) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var renamed = profileRepository.findById(profile.getId()).orElseThrow();
          renamed.setName(name);
          profileRepository.saveAndFlush(renamed);
        });
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
