package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The number of offers that wait for a Household's answer, which Household settings shows its
 * HouseholdAdmins, counted from the offers the Household's shares list.
 */
@Tag("IntegrationTest")
@DisplayName("Household Pending Offer Count Integration Tests")
class HouseholdPendingOfferCountIT extends AbstractIntegrationTest {

  private static final String HOUSEHOLD_ADMINISTRATION =
      """
      query { householdAdministration(householdId: "%s") { id pendingOfferCount } }
      """;

  private static final String HOUSEHOLDS =
      """
      query { households(first: 500) { edges { node { id pendingOfferCount } } } }
      """;

  private static final String PENDING_OFFERS =
      """
      query { householdShares(householdId: "%s", statuses: [PENDING]) { edges { node { id } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;
  @Autowired private ProfileShareLifecycle shareLifecycle;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should count the offers that wait when a HouseholdAdmin reads its Household")
  void shouldCountOffersThatWaitWhenHouseholdAdminReadsItsHousehold() throws Exception {
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    offer(cabin, cabin.profile(), smith);
    var member = authTestSupport.createHouseholdMemberIdentity(smith);
    var ownProfile =
        cast.unavailableProfileManagedBy(member, ProfileFixture.defaultProfileBuilder());
    offer(member, ownProfile, smith);
    var lodge = track(authTestSupport.createIdentity());
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.kidProfileBuilder()), lodge);

    assertThat(pendingOfferCount(smith, smith)).isEqualTo(3);
    assertThat(pendingOffers(authTestSupport.accountBearer(smith), smith)).hasSize(3);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"ACTIVE", "REJECTED", "CANCELED", "ENDED"})
  @DisplayName("Should not count a share when the offer has been answered")
  void shouldNotCountShareWhenOfferHasBeenAnswered(ProfileShareStatus status) throws Exception {
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    var answered =
        offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);

    for (var answer : answersReaching(status)) {
      perform(answer.by(), answer.mutation(), "{shareId: \"%s\"}".formatted(answered));
    }

    assertThat(pendingOfferCount(smith, smith)).isEqualTo(1);
    assertThat(pendingOffers(authTestSupport.accountBearer(smith), smith)).hasSize(1);
  }

  @Test
  @DisplayName("Should not count an offer when the server withdraws it")
  void shouldNotCountOfferWhenServerWithdrawsIt() throws Exception {
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    var withdrawn =
        offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);

    transactionTemplate.executeWithoutResult(
        _ ->
            shareLifecycle.tryInvalidatePending(
                UUID.fromString(withdrawn),
                ProfileShareLifecycle.OFFERER_NO_LONGER_AUTHORIZED,
                Instant.now()));

    assertThat(pendingOfferCount(smith, smith)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should not count an offer when its expiry has passed, before the sweep stores it and after")
  void shouldNotCountOfferWhenItsExpiryHasPassed() throws Exception {
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    var lapsed =
        offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);

    lapse(UUID.fromString(lapsed));

    assertThat(pendingOfferCount(smith, smith)).isEqualTo(1);
    assertThat(pendingOffers(authTestSupport.accountBearer(smith), smith)).hasSize(1);

    transactionTemplate.executeWithoutResult(_ -> shareLifecycle.expireLapsedOffers(Instant.now()));

    assertThat(pendingOfferCount(smith, smith)).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should count the offers made before its promotion when an Account becomes a"
          + " HouseholdAdmin")
  void shouldCountOffersMadeBeforePromotionWhenAccountBecomesHouseholdAdmin() throws Exception {
    var member = authTestSupport.createHouseholdMemberIdentity(smith);
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    offer(cabin, cabin.profile(), smith);
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    perform(serverAdmin, "grantHouseholdAdmin", accountInput(member));

    assertThat(pendingOfferCount(asItIsNow(member), smith)).isEqualTo(2);
  }

  @Test
  @DisplayName("Should count any Household's offers when a ServerAdmin reads it")
  void shouldCountAnyHouseholdsOffersWhenServerAdminReadsIt() throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    offer(cabin, cabin.profile(), smith);
    offer(smith, smith.profile(), cabin);

    assertThat(pendingOfferCount(serverAdmin, smith)).isEqualTo(2);
    assertThat(pendingOfferCounts(serverAdmin))
        .containsEntry(idOf(smith), 2)
        .containsEntry(idOf(cabin), 1)
        .containsEntry(idOf(serverAdmin), 0);
  }

  @Test
  @DisplayName(
      "Should count every Household of a page in the same number of statements when a ServerAdmin"
          + " lists more")
  void shouldCountEveryHouseholdOfPageInSameNumberOfStatementsWhenServerAdminListsMore()
      throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    offer(cabin, cabin.profile(), smith);
    offer(smith, smith.profile(), cabin);
    // The first request fills caches that later requests skip.
    pendingOfferCounts(serverAdmin);
    var forTwoWithOffers = statementCount.statementsWhile(() -> pendingOfferCounts(serverAdmin));

    var lodge = track(authTestSupport.createIdentity());
    var barn = track(authTestSupport.createIdentity());
    offer(lodge, lodge.profile(), barn);
    offer(barn, barn.profile(), lodge);
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), barn);

    assertThat(pendingOfferCounts(serverAdmin))
        .containsEntry(idOf(lodge), 1)
        .containsEntry(idOf(barn), 2);
    assertThat(statementCount.statementsWhile(() -> pendingOfferCounts(serverAdmin)))
        .isEqualTo(forTwoWithOffers);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Outsider.class)
  @DisplayName("Should give no count when the reader may not list the Household's offers")
  void shouldGiveNoCountWhenReaderMayNotListHouseholdsOffers(Outsider outsider) throws Exception {
    offer(cabin, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    var bearer = bearerOf(outsider);

    var household =
        data(bearer, HOUSEHOLD_ADMINISTRATION.formatted(smith.household().getId()))
            .path("householdAdministration");

    assertThat(household.isNull()).isTrue();
    assertThat(pendingOffers(bearer, smith)).isEmpty();
  }

  /** A reader of Smith's Household that may not list the offers into it. */
  enum Outsider {
    MEMBER_OF_THE_HOUSEHOLD,
    MANAGER_THAT_OFFERED,
    ADMIN_OF_ANOTHER_HOUSEHOLD,
    DEMOTED_ADMIN,
    DEVICE_BOUND_SESSION,
    DISABLED_ADMIN
  }

  private String bearerOf(Outsider outsider) throws Exception {
    return switch (outsider) {
      case MEMBER_OF_THE_HOUSEHOLD ->
          authTestSupport.accountBearer(authTestSupport.createHouseholdMemberIdentity(smith));
      case MANAGER_THAT_OFFERED -> authTestSupport.accountBearer(cabin);
      case ADMIN_OF_ANOTHER_HOUSEHOLD ->
          authTestSupport.accountBearer(track(authTestSupport.createIdentity()));
      case DEMOTED_ADMIN -> authTestSupport.accountBearer(demoted(cast.secondAdminOf(smith)));
      case DEVICE_BOUND_SESSION -> authTestSupport.deviceBoundProfileBearer(smith);
      case DISABLED_ADMIN -> {
        var admin = cast.secondAdminOf(smith);
        cast.enable(admin, false);
        yield authTestSupport.accountBearer(admin);
      }
    };
  }

  private TestIdentity demoted(TestIdentity admin) throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    perform(serverAdmin, "revokeHouseholdAdmin", accountInput(admin));
    return asItIsNow(admin);
  }

  /** Who sends which mutation, in order, to take a pending offer to the status. */
  private List<Answer> answersReaching(ProfileShareStatus status) {
    return switch (status) {
      case ACTIVE -> List.of(new Answer(smith, "acceptProfileShare"));
      case REJECTED -> List.of(new Answer(smith, "rejectProfileShare"));
      case CANCELED -> List.of(new Answer(cabin, "cancelProfileShare"));
      case ENDED ->
          List.of(new Answer(smith, "acceptProfileShare"), new Answer(smith, "endProfileShare"));
      case PENDING, EXPIRED, INVALIDATED ->
          throw new IllegalArgumentException("No answer takes an offer to " + status);
    };
  }

  private record Answer(TestIdentity by, String mutation) {}

  private int pendingOfferCount(TestIdentity reader, TestIdentity of) throws Exception {
    var count =
        data(
                authTestSupport.accountBearer(reader),
                HOUSEHOLD_ADMINISTRATION.formatted(of.household().getId()))
            .path("householdAdministration")
            .path("pendingOfferCount");
    assertThat(count.isInt()).as("a count of %s's offers", of.household().getName()).isTrue();
    return count.asInt();
  }

  /** Each Household's count, by its id, as a ServerAdmin's list of Households reads them. */
  private Map<String, Integer> pendingOfferCounts(TestIdentity serverAdmin) throws Exception {
    var counts = new HashMap<String, Integer>();
    data(authTestSupport.accountBearer(serverAdmin), HOUSEHOLDS)
        .path("households")
        .path("edges")
        .forEach(
            edge ->
                counts.put(
                    edge.path("node").path("id").asString(),
                    edge.path("node").path("pendingOfferCount").asInt()));
    return counts;
  }

  private List<String> pendingOffers(String bearer, TestIdentity into) throws Exception {
    var ids = new ArrayList<String>();
    data(bearer, PENDING_OFFERS.formatted(into.household().getId()))
        .path("householdShares")
        .path("edges")
        .forEach(edge -> ids.add(edge.path("node").path("id").asString()));
    return ids;
  }

  private String offer(TestIdentity offerer, Profile profile, TestIdentity into) throws Exception {
    var body =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(offerer),
                    """
                    mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                      share { id status } userErrors { __typename } } }
                    """
                        .formatted(profile.getId(), authTestSupport.shareCodeFor(into))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(body)
        .path("data")
        .path("offerProfileShare")
        .path("share")
        .path("id")
        .asString();
  }

  /** Sends the mutation as the caller and expects it done, with no error of either kind. */
  private void perform(TestIdentity caller, String mutation, String input) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(caller),
                "mutation { %s(input: %s) { userErrors { __typename } } }"
                    .formatted(mutation, input)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.%s.userErrors".formatted(mutation)).isEmpty());
  }

  private static String accountInput(TestIdentity account) {
    return "{accountId: \"%s\"}".formatted(account.account().getId());
  }

  private JsonNode data(String bearer, String query) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(bearer, query))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).path("data");
  }

  // An offer past its expiry reads as EXPIRED before the sweep stores it.
  private void lapse(UUID shareId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var offer = shareRepository.findById(shareId).orElseThrow();
          offer.setExpiresAt(Instant.now().minusSeconds(1));
          shareRepository.saveAndFlush(offer);
        });
  }

  // A token signs the Account's role, so a new token carries the role the Account has now.
  private TestIdentity asItIsNow(TestIdentity identity) {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    return new TestIdentity(
        account,
        identity.household(),
        identity.profile(),
        identity.session(),
        identity.rawRefreshToken());
  }

  private static String idOf(TestIdentity identity) {
    return identity.household().getId().toString();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
