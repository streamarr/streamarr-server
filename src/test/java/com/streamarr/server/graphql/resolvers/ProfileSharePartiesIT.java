package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Who sees the Profile, the offerer and the Household that a share names. */
@Tag("IntegrationTest")
@DisplayName("Profile Share Parties Integration Tests")
class ProfileSharePartiesIT extends AbstractIntegrationTest {

  private static final String PARTIES =
      """
      edges { node { id status householdId
        profile { id name picture kind personalProfile restricted }
        offeredByDisplayName
        household { id name } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    owner = identity();
    host = identity();
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should name every party when the target HouseholdAdmin lists the offers")
  void shouldNameEveryPartyWhenTargetHouseholdAdminListsOffers() throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder().picture("lantern"));
    var shareId = offer(owner, orphan, host);

    var share = onlyShare(householdShares(host, host, "[PENDING]"));

    assertThat(share.path("id").asString()).isEqualTo(shareId);
    assertThat(share.path("profile").path("id").asString()).isEqualTo(orphan.getId().toString());
    assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    assertThat(share.path("profile").path("picture").asString()).isEqualTo("lantern");
    assertThat(share.path("profile").path("kind").asString()).isEqualTo("ADULT");
    assertThat(share.path("profile").path("personalProfile").asBoolean()).isFalse();
    assertThat(share.path("profile").path("restricted").asBoolean()).isFalse();
    assertThat(share.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(share.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
  }

  @Test
  @DisplayName("Should mark a Personal Profile when its own Account offers it")
  void shouldMarkPersonalProfileWhenItsOwnAccountOffersIt() throws Exception {
    offer(owner, owner.profile(), host);

    var profile = onlyShare(householdShares(host, host, "[PENDING]")).path("profile");

    assertThat(profile.path("personalProfile").asBoolean()).isTrue();
    assertThat(profile.path("restricted").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("Should mark a restricted Profile when a Kid is offered")
  void shouldMarkRestrictedProfileWhenKidIsOffered() throws Exception {
    offer(owner, managedProfile(ProfileFixture.kidProfileBuilder()), host);

    var profile = onlyShare(householdShares(host, host, "[PENDING]")).path("profile");

    assertThat(profile.path("kind").asString()).isEqualTo("KID");
    assertThat(profile.path("restricted").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should name every party when a ServerAdmin lists a Household's shares")
  void shouldNameEveryPartyWhenServerAdminListsHouseholdsShares() throws Exception {
    offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    var serverAdmin = adminIdentity();

    var share = onlyShare(householdShares(serverAdmin, host, "[PENDING]"));

    assertThat(share.path("profile").isObject()).isTrue();
    assertThat(share.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(share.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
  }

  @Test
  @DisplayName("Should show the offering side the Household's name when the offer is declined")
  void shouldShowOfferingSideHouseholdNameWhenOfferIsDeclined() throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = offer(owner, orphan, host);
    decline(host, shareId);

    var share = shareWithId(profileShares(owner, orphan, "[REJECTED]"), shareId);

    assertThat(share.path("status").asString()).isEqualTo("REJECTED");
    assertThat(share.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    // The offerer's name belongs to the target side.
    assertThat(share.path("offeredByDisplayName").isNull()).isTrue();
  }

  @Test
  @DisplayName("Should show the offering side the Household's name when it withdraws the offer")
  void shouldShowOfferingSideHouseholdNameWhenItWithdrawsOffer() throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = offer(owner, orphan, host);

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { cancelProfileShare(input: {shareId: "%s"}) {
              share { status household { name } } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.cancelProfileShare.share.status").value("CANCELED"))
        .andExpect(
            jsonPath("$.data.cancelProfileShare.share.household.name")
                .value(host.household().getName()));
  }

  @Test
  @DisplayName(
      "Should not list where else a Profile visits when a hosting HouseholdAdmin lists its shares")
  void shouldNotListWhereElseProfileVisitsWhenHostingHouseholdAdminListsItsShares()
      throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var hostedShareId = offer(owner, orphan, host);
    accept(host, hostedShareId);
    offer(owner, orphan, identity());

    var shares = profileShares(host, orphan, "[]");

    // The host reads the share into its own Household through householdShares, and no other.
    assertThat(edges(shares)).isEmpty();
    assertThat(shareWithId(householdShares(host, host, "[ACTIVE]"), hostedShareId)).isNotNull();
  }

  @Test
  @DisplayName(
      "Should show every party when the hosting HouseholdAdmin lists a share into its Household")
  void shouldShowEveryPartyWhenHostingHouseholdAdminListsShareIntoItsHousehold() throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = offer(owner, orphan, host);
    accept(host, shareId);

    var share = shareWithId(householdShares(host, host, "[ACTIVE]"), shareId);

    assertThat(share.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(share.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
  }

  @Test
  @DisplayName(
      "Should not tell the target HouseholdAdmin how the offerer lost authority when a"
          + " ServerAdmin disables the offerer")
  void shouldNotTellTargetHouseholdAdminHowOffererLostAuthorityWhenServerAdminDisablesOfferer()
      throws Exception {
    var shareId = offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    graphql(
            authTestSupport.freshAccountBearer(adminIdentity()),
            """
            mutation { disableAccount(input: {accountId: "%s"}) {
              account { enabled } userErrors { __typename } } }
            """
                .formatted(owner.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.disableAccount.account.enabled").value(false));

    var withdrawn =
        shareWithId(
            query(
                host,
                """
                query { householdShares(householdId: "%s", statuses: [INVALIDATED]) {
                  edges { node { id invalidationReason offeredByDisplayName } } } }
                """
                    .formatted(host.household().getId()),
                "householdShares"),
            shareId);

    // Account status and roles are for the Account's own HouseholdAdmins and ServerAdmins.
    assertThat(withdrawn.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(withdrawn.path("invalidationReason").asString())
        .isEqualTo("offerer no longer authorized");
  }

  @Test
  @DisplayName("Should name no offerer when the share was made by the system")
  void shouldNameNoOffererWhenShareWasMadeBySystem() throws Exception {
    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { householdShares(householdId: "%s", statuses: [ACTIVE]) {
              edges { node { requiredByAccountMembership offeredByDisplayName
                profile { personalProfile } household { name } } } } }
            """
                .formatted(owner.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.householdShares.edges[0].node.requiredByAccountMembership")
                .value(true))
        .andExpect(
            jsonPath("$.data.householdShares.edges[0].node.offeredByDisplayName")
                .value(nullValue()))
        .andExpect(
            jsonPath("$.data.householdShares.edges[0].node.profile.personalProfile").value(true))
        .andExpect(
            jsonPath("$.data.householdShares.edges[0].node.household.name")
                .value(owner.household().getName()));
  }

  @Test
  @DisplayName(
      "Should read a Household's offers in the same number of statements when it holds more")
  void shouldReadHouseholdsOffersInTheSameNumberOfStatementsWhenItHoldsMore() throws Exception {
    offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    offer(owner, owner.profile(), host);
    // The first request fills caches that later requests skip.
    householdShares(host, host, "[PENDING]");
    var forTwoOffers = statementsWhile(() -> householdShares(host, host, "[PENDING]"));

    var other = identity();
    offer(other, other.profile(), host);
    offer(owner, managedProfile(ProfileFixture.kidProfileBuilder()), host);

    assertThat(edges(householdShares(host, host, "[PENDING]"))).hasSize(4);
    assertThat(statementsWhile(() -> householdShares(host, host, "[PENDING]")))
        .isEqualTo(forTwoOffers);
  }

  // The offerer's name is left out: it is decided once for each Household on the page.
  @Test
  @DisplayName(
      "Should read a Profile's shares in the same number of statements when it visits more")
  void shouldReadProfilesSharesInTheSameNumberOfStatementsWhenItVisitsMore() throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    offer(owner, orphan, host);
    offer(owner, orphan, identity());
    var query =
        """
        query { profileShares(profileId: "%s", statuses: [PENDING]) {
          edges { node { id profile { name personalProfile } household { name } } } } }
        """
            .formatted(orphan.getId());
    query(owner, query, "profileShares");
    var forTwoOffers = statementsWhile(() -> query(owner, query, "profileShares"));

    offer(owner, orphan, identity());
    offer(owner, orphan, identity());

    assertThat(edges(query(owner, query, "profileShares")))
        .hasSize(4)
        .allSatisfy(share -> assertThat(share.path("household").path("name").isString()).isTrue());
    assertThat(statementsWhile(() -> query(owner, query, "profileShares"))).isEqualTo(forTwoOffers);
  }

  private JsonNode householdShares(
      AuthTestSupport.TestIdentity reader, AuthTestSupport.TestIdentity of, String statuses)
      throws Exception {
    return query(
        reader,
        "query { householdShares(householdId: \"%s\", statuses: %s) { %s } }"
            .formatted(of.household().getId(), statuses, PARTIES),
        "householdShares");
  }

  private JsonNode profileShares(AuthTestSupport.TestIdentity reader, Profile of, String statuses)
      throws Exception {
    return query(
        reader,
        "query { profileShares(profileId: \"%s\", statuses: %s) { %s } }"
            .formatted(of.getId(), statuses, PARTIES),
        "profileShares");
  }

  private JsonNode query(AuthTestSupport.TestIdentity reader, String query, String field)
      throws Exception {
    var response =
        graphql(authTestSupport.accountBearer(reader), query)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).path("data").path(field);
  }

  private static List<JsonNode> edges(JsonNode connection) {
    var nodes = new ArrayList<JsonNode>();
    connection.path("edges").forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  private static JsonNode onlyShare(JsonNode connection) {
    var nodes = edges(connection);
    assertThat(nodes).hasSize(1);
    return nodes.getFirst();
  }

  private static JsonNode shareWithId(JsonNode connection, String shareId) {
    return edges(connection).stream()
        .filter(node -> node.path("id").asString().equals(shareId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no share " + shareId + " in " + connection));
  }

  private String offer(
      AuthTestSupport.TestIdentity offerer, Profile profile, AuthTestSupport.TestIdentity target)
      throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(offerer),
                """
                mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                  share { id status } userErrors { __typename } } }
                """
                    .formatted(profile.getId(), target.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(response)
        .path("data")
        .path("offerProfileShare")
        .path("share")
        .path("id")
        .asString();
  }

  private void accept(AuthTestSupport.TestIdentity decider, String shareId) throws Exception {
    graphql(
            authTestSupport.accountBearer(decider),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"));
  }

  private void decline(AuthTestSupport.TestIdentity decider, String shareId) throws Exception {
    graphql(
            authTestSupport.accountBearer(decider),
            """
            mutation { rejectProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));
  }

  /** A Profile the owner solely manages, available in the owner's Household. */
  private Profile managedProfile(Profile.ProfileBuilder<?, ?> builder) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  builder.householdId(owner.household().getId()).build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private AuthTestSupport.TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  private AuthTestSupport.TestIdentity adminIdentity() {
    var identity = authTestSupport.createAdminIdentity();
    identities.add(identity);
    return identity;
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  @FunctionalInterface
  private interface Request {
    void run() throws Exception;
  }

  // Hibernate statistics are global, so the count holds only while no other test uses the context.
  private long statementsWhile(Request request) throws Exception {
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
