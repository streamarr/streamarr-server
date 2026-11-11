package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
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
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

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
                .value(
                    "%s offered the Profile Kai to your Household."
                        .formatted(sam.account().getDisplayName())))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value("Kai"))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(true))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").doesNotExist())
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.expiresAt")
                .value(offer.getExpiresAt().toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.share.id").value(offer.getId().toString()))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.share.status").value("PENDING"))
        .andExpect(jsonPath("$.data.notifications.edges[0].orderKey").isString())
        .andExpect(jsonPath("$.data.notifications.pageInfo.hasNextPage").value(false));
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
                .value("The Profile Kai was offered to your Household."));
  }

  @Test
  @DisplayName(
      "Should give the offering side its news under the Profile's current name when the offer is"
          + " accepted")
  void shouldGiveTheOfferingSideItsNewsUnderTheCurrentNameWhenTheOfferIsAccepted()
      throws Exception {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var offer = steps.offer(sam, kai, riverside);
    steps.accept(riverside, offer);
    rename(kai, "Kai Renamed");

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(theo), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(1))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.__typename")
                .value("ProfileShareAnsweredNotification"))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.answer").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(true))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.profileName").value("Kai Renamed"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value(
                    "%s accepted the Profile Kai Renamed."
                        .formatted(riverside.household().getName())));
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
  @DisplayName("Should continue from the cursor of the last Notification of the previous page")
  void shouldContinueFromTheCursorOfTheLastNotificationOfThePreviousPage() throws Exception {
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
      "Should read a page of many Notifications in as many statements as a page of a few when"
          + " they name different Profiles")
  void shouldReadAPageOfManyNotificationsInAsManyStatementsAsAPageOfAFew() throws Exception {
    var bearer = authTestSupport.accountBearer(riversideAdmin);
    offerProfiles(2);
    var few = statementsWhile(() -> mockMvc.perform(graphqlRequest(bearer, NOTIFICATIONS)));
    offerProfiles(4);

    var many = statementsWhile(() -> mockMvc.perform(graphqlRequest(bearer, NOTIFICATIONS)));

    assertThat(many).isEqualTo(few);
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
