package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.NotificationDraft;
import com.streamarr.server.domain.notification.NotificationKind;
import com.streamarr.server.domain.notification.ProfileShareAnswer;
import com.streamarr.server.domain.notification.ProfileShareFacts;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.ShareNotificationRepository;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ShareSteps;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Notifications Query Integration Tests")
class NotificationsQueryIT extends AbstractIntegrationTest {

  private static final int COUNTED_RUNS = 3;

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
              ... on RequestNotification { expiresAt resolution }
              ... on ProfileShareOfferedNotification { profileName share { id status } }
              ... on ProfileShareAnsweredNotification { profileName answer share { id } }
              ... on ProfileShareEndedNotification { profileName endedBy share { id } }
            }
          }
          pageInfo { hasNextPage endCursor }
        }
      }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private ShareNotificationRepository notificationRepository;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;
  private int offeredProfiles;

  @BeforeEach
  void setUp() {
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
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should list a waiting request with its line, the Profile's name and the offer when a"
          + " Profile is offered to the reader's Household")
  void shouldListAWaitingRequestWithItsLineWhenAProfileIsOfferedToTheReadersHousehold()
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
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(true))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").doesNotExist())
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.expiresAt").value(storedExpiry.toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.share.id").value(offer.getId().toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.share.status").value("PENDING"))
        .andExpect(jsonPath("$.data.notifications.edges[0].orderKey").isString())
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(false));
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
      "Should leave a request out of the count and read it as expired when its offer's time ran out"
          + " and the sweep has not stored it")
  void shouldLeaveARequestOutOfTheCountAndReadItAsExpiredWhenItsOffersTimeRanOutBeforeTheSweep()
      throws Exception {
    steps.lapse(steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside));

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(false))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("EXPIRED"));
  }

  @Test
  @DisplayName(
      "Should give the time of the first event and the time of the latest when news is told again")
  void shouldGiveTheTimeOfTheFirstEventAndTheTimeOfTheLatestWhenNewsIsToldAgain() throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var firstEvent = Instant.now().plus(Duration.ofMinutes(1)).truncatedTo(ChronoUnit.MICROS);
    var latestEvent = firstEvent.plus(Duration.ofMinutes(1));
    var news = tellNews(riversideAdmin, offer, firstEvent);
    var madeAt = notificationRepository.findById(news).orElseThrow().getCreatedOn();
    tellNews(riversideAdmin, offer, latestEvent);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(riversideAdmin), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notifications.edges[0].node.id").value(news.toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.createdAt").value(madeAt.toString()))
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
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var later = Instant.now().plus(Duration.ofMinutes(1));
    requestAgain(riversideAdmin, offer, later);
    requestAgain(riversideAdmin, offer, later.plus(Duration.ofMinutes(1)));
    requestAgain(riversideAdmin, offer, later.plus(Duration.ofMinutes(1)));

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
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    for (var made = 1; made <= 20; made++) {
      requestAgain(riversideAdmin, offer, Instant.now().plus(Duration.ofMinutes(made)));
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
  @DisplayName(
      "Should read the resolved request as read under the kept name when its offer was rejected"
          + " and the Profile was renamed")
  void shouldReadTheResolvedRequestAsReadUnderTheKeptNameWhenItsOfferWasRejected()
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
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value("Kai"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("“Kai” was offered to your Household."));
  }

  @Test
  @DisplayName(
      "Should neither list nor count the request when its recipient no longer administers the"
          + " Household, even on a token that still names the role")
  void shouldNeitherListNorCountTheRequestWhenItsRecipientNoLongerAdministersTheHousehold()
      throws Exception {
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDemoteToHouseholdMember(riversideAdmin.account().getId()));

    mockMvc
        .perform(graphqlRequest(bearer, NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges").isEmpty());
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
    var first = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var second = steps.offer(sam, steps.profileManagedBy("Ollie", sam, sam), riverside);
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    var firstPage =
        mockMvc
            .perform(
                graphqlRequest(
                    bearer,
                    """
                    query { notifications(first: 1) {
                      edges { cursor node { ... on ProfileShareOfferedNotification { share { id } } } }
                      pageInfo { hasNextPage } } }
                    """))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.notifications.edges[0].node.share.id")
                    .value(second.getId().toString()))
            .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(true))
            .andReturn();
    String cursor =
        JsonPath.read(
            firstPage.getResponse().getContentAsString(), "$.data.notifications.edges[0].cursor");

    mockMvc
        .perform(
            graphqlRequest(
                bearer,
                "query { notifications(first: 1, after: \"%s\") { edges { node { ... on ProfileShareOfferedNotification { share { id } } } } pageInfo { hasNextPage } } }"
                    .formatted(cursor)))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.share.id").value(first.getId().toString()))
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(false));
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
      "Should read a page of many Notifications in as many statements as a page of a few when"
          + " they name different Profiles")
  void shouldReadAPageOfManyNotificationsInAsManyStatementsWhenTheyNameDifferentProfiles()
      throws Exception {
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    offerProfiles(2);
    var few = statementsWhile(() -> readsPage(bearer, 2));
    offerProfiles(4);

    var many = statementsWhile(() -> readsPage(bearer, 6));

    assertThat(many).isEqualTo(few);
  }

  @Test
  @DisplayName(
      "Should read a page in as many statements when many of the reader's Notifications are hidden"
          + " from it as when few are")
  void shouldReadAPageInAsManyStatementsWhenManyOfTheReadersNotificationsAreHiddenAsWhenFewAre()
      throws Exception {
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    var kai = track(authTestSupport.createIdentity());
    steps.offer(sam, steps.profileManagedBy("Ollie", sam, sam), riverside);
    hideFromRiversideAdmin(2, kai);
    var few = statementsWhile(() -> readsPage(bearer, 1));
    hideFromRiversideAdmin(24, kai);

    var many = statementsWhile(() -> readsPage(bearer, 1));

    assertThat(many).isEqualTo(few);
  }

  @Test
  @DisplayName(
      "Should list and count the request again when its recipient administers the Household again")
  void shouldListAndCountTheRequestAgainWhenItsRecipientAdministersTheHouseholdAgain()
      throws Exception {
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDemoteToHouseholdMember(riversideAdmin.account().getId()));
    mockMvc
        .perform(graphqlRequest(bearer, NOTIFICATIONS))
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0));

    steps.promoted(riversideAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)));
  }

  @Test
  @DisplayName(
      "Should neither list nor count news about a share when its offerer stopped managing the"
          + " Profile and only administers another Household that hosts it")
  void shouldNeitherListNorCountNewsAboutAShareWhenItsOffererStoppedManagingTheProfile()
      throws Exception {
    var brook = track(authTestSupport.createIdentity());
    var kai = steps.profileManagedBy("Kai", sam, sam, brook);
    var intoBrook = steps.accept(brook, steps.offer(sam, kai, brook));
    steps.accept(riverside, steps.offer(brook, kai, riverside));
    assertThat(
            profileManagement.relinquishProfileManagement(
                authTestSupport.identityOf(brook), kai.getId()))
        .isInstanceOf(Outcome.Accepted.class);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(brook), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.share.id",
                contains(intoBrook.getId().toString())));
  }

  @Test
  @DisplayName(
      "Should neither list nor count news told to the offering side when its recipient stopped"
          + " managing the Profile before the share ended and has since become a HouseholdAdmin of"
          + " the Household the share was into")
  void shouldNeitherListNorCountOfferingSideNewsWhenItsRecipientNowAdministersTheTargetHousehold()
      throws Exception {
    var brook = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var kai = steps.profileManagedBy("Kai", sam, sam, brook);
    var share = steps.accept(riverside, steps.offer(brook, kai, riverside));
    assertThat(
            profileManagement.relinquishProfileManagement(
                authTestSupport.identityOf(brook), kai.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(sharing.endProfileShare(authTestSupport.identityOf(riverside), share.getId()))
        .isInstanceOf(Outcome.Accepted.class);
    assertThat(notificationRepository.findAll())
        .anyMatch(told -> told.getRecipientAccountId().equals(brook.account().getId()));
    var promoted = steps.promoted(brook);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(promoted), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges").isEmpty());
  }

  @Test
  @DisplayName(
      "Should keep giving the Household's side its end news and its resolved request, with the"
          + " share as the end left it and neither its Profile nor its offerer, when a ProfileManager"
          + " ends the share")
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
        .perform(
            graphqlRequest(
                bearer,
                """
                query {
                  unreadNotificationCount
                  notifications(first: 10) { edges { node {
                    __typename unread
                    ... on RequestNotification { resolution }
                    ... on ProfileShareOfferedNotification {
                      share { id status endedAt profile { id } offeredByDisplayName }
                    }
                    ... on ProfileShareEndedNotification {
                      endedBy share { id status endedAt profile { id } offeredByDisplayName }
                    }
                  } } }
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.__typename",
                contains("ProfileShareEndedNotification", "ProfileShareOfferedNotification")))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.endedBy").value("OFFERING_SIDE"))
        .andExpect(jsonPath("$.data.notifications.edges[1].node.resolution").value("DECIDED"))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.share.id",
                contains(share.getId().toString(), share.getId().toString())))
        .andExpect(
            jsonPath("$.data.notifications.edges[*].node.share.status", contains("ENDED", "ENDED")))
        .andExpect(
            jsonPath("$.data.notifications.edges[*].node.share.endedAt", everyItem(notNullValue())))
        .andExpect(
            jsonPath("$.data.notifications.edges[*].node.share.profile", everyItem(nullValue())))
        .andExpect(
            jsonPath(
                "$.data.notifications.edges[*].node.share.offeredByDisplayName",
                everyItem(nullValue())));
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

  // Requests to Riverside's HouseholdAdmin about offers into Kai's Household, each of its own
  // Profile and newer than the rest; Riverside's HouseholdAdmin may read none of them.
  private void hideFromRiversideAdmin(int count, TestIdentity kai) {
    for (var made = 0; made < count; made++) {
      offeredProfiles++;
      var offer =
          steps.offer(sam, steps.profileManagedBy("Hidden " + offeredProfiles, sam, sam), kai);
      var at = Instant.now().plus(Duration.ofMinutes(offeredProfiles));
      transactionTemplate.executeWithoutResult(
          _ ->
              notificationRepository.insert(
                  NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
                      .recipientAccountId(riversideAdmin.account().getId())
                      .kind(NotificationKind.PROFILE_SHARE_OFFERED)
                      .subjectId(offer.getId())
                      .facts(ProfileShareFacts.offered("Hidden"))
                      .at(at)
                      .build()));
    }
  }

  /** The page reads without an error, and holds as many Notifications as its unread count. */
  private void readsPage(String bearer, int unread) throws Exception {
    mockMvc
        .perform(graphqlRequest(bearer, NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(unread))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(unread)));
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

  /** News to the recipient that the offer was accepted, stored as the writer stores news. */
  private UUID tellNews(TestIdentity recipient, ProfileHouseholdShare offer, Instant at) {
    return transactionTemplate.execute(
        _ ->
            notificationRepository
                .upsertUnreadNews(
                    NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
                        .recipientAccountId(recipient.account().getId())
                        .kind(NotificationKind.PROFILE_SHARE_ANSWERED)
                        .subjectId(offer.getId())
                        .facts(ProfileShareFacts.answered("Kai", ProfileShareAnswer.ACCEPTED))
                        .at(at)
                        .build())
                .notificationId());
  }

  /** One more request to the recipient about the offer, its event at the instant. */
  private void requestAgain(TestIdentity recipient, ProfileHouseholdShare offer, Instant at) {
    transactionTemplate.executeWithoutResult(
        _ ->
            notificationRepository.insert(
                NotificationDraft.<NotificationKind, ProfileShareFacts>builder()
                    .recipientAccountId(recipient.account().getId())
                    .kind(NotificationKind.PROFILE_SHARE_OFFERED)
                    .subjectId(offer.getId())
                    .facts(ProfileShareFacts.offered("Kai"))
                    .at(at)
                    .build()));
  }

  private void offerProfiles(int count) {
    for (var made = 0; made < count; made++) {
      offeredProfiles++;
      steps.offer(sam, steps.profileManagedBy("Kai " + offeredProfiles, sam, sam), riverside);
    }
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

  private interface Request {
    void run() throws Exception;
  }

  // Hibernate statistics are global, so the count holds only while no other test uses the context.
  // A background thread can add a statement of its own to one run; the fewest over a few runs is
  // the request's.
  private long statementsWhile(Request request) throws Exception {
    var fewest = Long.MAX_VALUE;
    for (var run = 0; run < COUNTED_RUNS; run++) {
      fewest = Math.min(fewest, statementsOfOneRun(request));
    }

    return fewest;
  }

  private long statementsOfOneRun(Request request) throws Exception {
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    var previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    try {
      request.run();
      return statistics.getPrepareStatementCount();
    } finally {
      statistics.clear();
      statistics.setStatisticsEnabled(previouslyEnabled);
    }
  }
}
