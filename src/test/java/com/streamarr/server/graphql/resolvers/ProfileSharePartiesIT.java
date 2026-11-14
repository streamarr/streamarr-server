package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Who sees the Profile, the offerer and the Household that a share names, and who is handed a
 * change of the share.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Share Parties Integration Tests")
class ProfileSharePartiesIT extends AbstractIntegrationTest {

  private static final String PARTIES =
      """
      edges { node { id status householdId
        profile { id name picture kind personalProfile restricted }
        visitor
        offeredByDisplayName
        household { id name } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileSharingService profileSharingService;
  @Autowired private ProfileShareLifecycle shareLifecycle;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private StatementCount statementCount;

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
  @DisplayName(
      "Should name the Profile and the offerer but not the Household when the target"
          + " HouseholdAdmin lists the offers")
  void shouldNameProfileAndOffererButNotHouseholdWhenTargetHouseholdAdminListsOffers()
      throws Exception {
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
    // The Household's name on a share is for the Profile's managers and ServerAdmins.
    assertThat(share.path("household").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "Should name the Household when a self-managed Account lists the shares of its own Profile")
  void shouldNameHouseholdWhenSelfManagedAccountListsSharesOfItsOwnProfile() throws Exception {
    var shareId = offer(owner, owner.profile(), host);

    var share = shareWithId(profileShares(owner, owner.profile(), "[PENDING]"), shareId);

    assertThat(share.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(
            profileSharingService.shareChange(
                authTestSupport.identityOf(owner), changeOf(UUID.fromString(shareId))))
        .isPresent();
  }

  @Test
  @DisplayName(
      "Should show a supervised person no share of their own Profile when they list its shares or"
          + " follow its changes")
  void shouldShowSupervisedPersonNoShareOfTheirOwnProfileWhenTheyListItsSharesOrFollowItsChanges()
      throws Exception {
    var supervised = supervisedMemberOf(owner);
    var shareId = offer(owner, supervised.profile(), host);
    var offered = changeOf(UUID.fromString(shareId));

    var forSupervised = profileShares(supervised, supervised.profile(), "[]");
    var forManager = shareWithId(profileShares(owner, supervised.profile(), "[]"), shareId);

    // An Account whose Personal Profile is supervised is not one of its ProfileManagers.
    assertThat(edges(forSupervised)).isEmpty();
    assertThat(profileSharingService.shareChange(authTestSupport.identityOf(supervised), offered))
        .isEmpty();
    assertThat(forManager.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(profileSharingService.shareChange(authTestSupport.identityOf(owner), offered))
        .isPresent();
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
  @DisplayName(
      "Should tell a visitor from the Household's own Profile when anyone who sees the Profile"
          + " lists its shares")
  void shouldTellVisitorFromHouseholdsOwnProfileWhenAnyoneWhoSeesProfileListsItsShares()
      throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var visitId = offer(owner, orphan, host);
    var hostsOwnId = activeShareId(host.profile(), host);
    var orphansOwnId = activeShareId(orphan, owner);
    var serverAdmin = adminIdentity();

    var forHost = householdShares(host, host, "[]");
    var forServerAdmin = householdShares(serverAdmin, host, "[]");
    var forOwner = profileShares(owner, orphan, "[]");

    var intoHost = Map.of(visitId, "true", hostsOwnId, "false");
    assertThat(visitorById(forHost)).containsExactlyInAnyOrderEntriesOf(intoHost);
    assertThat(visitorById(forServerAdmin)).containsExactlyInAnyOrderEntriesOf(intoHost);
    assertThat(visitorById(forOwner))
        .containsExactlyInAnyOrderEntriesOf(Map.of(visitId, "true", orphansOwnId, "false"));
    // The visitor mark is part of the share's Profile: whoever sees one sees the other.
    assertThat(List.of(forHost, forServerAdmin, forOwner))
        .flatMap(ProfileSharePartiesIT::edges)
        .hasSize(6)
        .allSatisfy(node -> assertThat(node.path("profile").isObject()).isTrue());
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
      "Should name the Profile and the offerer but not the Household when the hosting"
          + " HouseholdAdmin lists a share into its Household")
  void shouldNameProfileAndOffererButNotHouseholdWhenHostingHouseholdAdminListsShareIntoIt()
      throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = offer(owner, orphan, host);
    accept(host, shareId);

    var share = shareWithId(householdShares(host, host, "[ACTIVE]"), shareId);

    assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    assertThat(share.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(share.path("household").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "Should not say how the offerer lost authority when a ServerAdmin disables the offerer")
  void shouldNotSayHowOffererLostAuthorityWhenServerAdminDisablesOfferer() throws Exception {
    var shareId = offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    var serverAdmin = adminIdentity();
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { disableAccount(input: {accountId: "%s"}) {
              account { enabled } userErrors { __typename } } }
            """
                .formatted(owner.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.disableAccount.account.enabled").value(false));
    var withdrawnQuery =
        """
        query { householdShares(householdId: "%s", statuses: [INVALIDATED]) {
          edges { node { id invalidationReason offeredByDisplayName } } } }
        """
            .formatted(host.household().getId());

    var withdrawn = shareWithId(query(serverAdmin, withdrawnQuery, "householdShares"), shareId);

    // Account status and roles are for the Account's own HouseholdAdmins and ServerAdmins.
    assertThat(withdrawn.path("invalidationReason").asString())
        .isEqualTo("offerer no longer authorized");
    assertThat(withdrawn.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    // The target Household's HouseholdAdmins list only live shares; the change told them.
    assertThat(edges(query(host, withdrawnQuery, "householdShares"))).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"PENDING", "ACTIVE"})
  @DisplayName(
      "Should show the target HouseholdAdmin the Profile and the offerer but not the Household"
          + " when the offer is pending or the share is active")
  void shouldShowTargetHouseholdAdminProfileAndOffererButNotHouseholdWhenOfferIsPendingOrActive(
      ProfileShareStatus status) throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = shareAt(status, orphan);

    var share = shareWithId(householdShares(host, host, "[]"), shareId);

    assertThat(share.path("status").asString()).isEqualTo(status.name());
    assertThat(share.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    assertThat(share.path("visitor").asBoolean()).isTrue();
    assertThat(share.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(share.path("household").isNull()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileShareStatus.class,
      names = {"REJECTED", "CANCELED", "EXPIRED", "INVALIDATED", "ENDED"})
  @DisplayName(
      "Should hide the share from the target HouseholdAdmin and show it whole to a ServerAdmin"
          + " when the offer or the share is over")
  void shouldHideShareFromTargetHouseholdAdminAndShowItWholeToServerAdminWhenOfferOrShareIsOver(
      ProfileShareStatus status) throws Exception {
    var orphan = managedProfile(ProfileFixture.defaultProfileBuilder());
    var shareId = shareAt(status, orphan);
    var serverAdmin = adminIdentity();
    var named = "[%s]".formatted(status);

    var forHost = householdShares(host, host, "[]");
    var forHostByStatus = householdShares(host, host, named);
    var forServerAdmin = shareWithId(householdShares(serverAdmin, host, named), shareId);
    var forManager = shareWithId(profileShares(owner, orphan, named), shareId);

    // ADR 0024: a Household that turned someone down, or that a person left, sees none of them.
    assertThat(edges(forHost))
        .extracting(node -> node.path("id").asString())
        .hasSize(1)
        .doesNotContain(shareId);
    assertThat(edges(forHostByStatus)).isEmpty();
    assertThat(forServerAdmin.path("status").asString()).isEqualTo(status.name());
    assertThat(forServerAdmin.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    assertThat(forServerAdmin.path("offeredByDisplayName").asString())
        .isEqualTo(owner.account().getDisplayName());
    assertThat(forServerAdmin.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(forManager.path("profile").path("name").asString()).isEqualTo(orphan.getName());
    assertThat(forManager.path("household").path("name").asString())
        .isEqualTo(host.household().getName());
    assertThat(forManager.path("offeredByDisplayName").isNull()).isTrue();
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
      "Should read every party of a Household's shares for a ServerAdmin in the same number of"
          + " statements when it holds more")
  void shouldReadEveryPartyOfHouseholdsSharesForServerAdminInSameNumberOfStatementsWhenItHoldsMore()
      throws Exception {
    var serverAdmin = adminIdentity();
    offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    offer(owner, owner.profile(), host);
    householdShares(serverAdmin, host, "[]");
    var forTwoOffers =
        statementCount.statementsWhile(() -> householdShares(serverAdmin, host, "[]"));

    var other = identity();
    offer(other, other.profile(), host);
    offer(owner, managedProfile(ProfileFixture.kidProfileBuilder()), host);

    assertThat(edges(householdShares(serverAdmin, host, "[]")))
        .hasSize(5)
        .allSatisfy(share -> assertThat(share.path("household").path("name").isString()).isTrue());
    assertThat(statementCount.statementsWhile(() -> householdShares(serverAdmin, host, "[]")))
        .isEqualTo(forTwoOffers);
  }

  @Test
  @DisplayName(
      "Should read every party of a Household's offers for its HouseholdAdmin in the same number"
          + " of statements when it holds more")
  void shouldReadEveryPartyOfHouseholdsOffersForItsAdminInSameNumberOfStatementsWhenItHoldsMore()
      throws Exception {
    offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);
    offer(owner, owner.profile(), host);
    // The first request fills caches that later requests skip.
    householdShares(host, host, "[PENDING]");
    var forTwoOffers =
        statementCount.statementsWhile(() -> householdShares(host, host, "[PENDING]"));

    var other = identity();
    offer(other, other.profile(), host);
    offer(owner, managedProfile(ProfileFixture.defaultProfileBuilder()), host);

    assertThat(edges(householdShares(host, host, "[PENDING]")))
        .hasSize(4)
        .allSatisfy(share -> assertThat(share.path("household").isNull()).isTrue());
    assertThat(statementCount.statementsWhile(() -> householdShares(host, host, "[PENDING]")))
        .isEqualTo(forTwoOffers);
  }

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
          edges { node { id profile { name personalProfile } offeredByDisplayName
            household { name } } } } }
        """
            .formatted(orphan.getId());
    query(owner, query, "profileShares");
    var forTwoOffers = statementCount.statementsWhile(() -> query(owner, query, "profileShares"));

    offer(owner, orphan, identity());
    offer(owner, orphan, identity());

    assertThat(edges(query(owner, query, "profileShares")))
        .hasSize(4)
        .allSatisfy(share -> assertThat(share.path("household").path("name").isString()).isTrue());
    assertThat(statementCount.statementsWhile(() -> query(owner, query, "profileShares")))
        .isEqualTo(forTwoOffers);
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

  // Each share's visitor mark as the response reads it: true, false or null.
  private static Map<String, String> visitorById(JsonNode connection) {
    var visitors = new HashMap<String, String>();
    edges(connection)
        .forEach(node -> visitors.put(node.path("id").asString(), node.path("visitor").toString()));
    return visitors;
  }

  private String activeShareId(Profile profile, AuthTestSupport.TestIdentity into) {
    return shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profile.getId(), into.household().getId(), ProfileShareStatus.ACTIVE)
        .orElseThrow()
        .getId()
        .toString();
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

  /** A member of the Household whose Personal Profile is a Kid, supervised by its admin. */
  private AuthTestSupport.TestIdentity supervisedMemberOf(AuthTestSupport.TestIdentity admin) {
    var member = authTestSupport.createHouseholdMemberIdentity(admin);
    identities.add(member);
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(member.profile().getId())
                  .build());
          var profile = profileRepository.findById(member.profile().getId()).orElseThrow();
          profile.setKind(ProfileKind.KID);
          profileRepository.saveAndFlush(profile);
        });
    return member;
  }

  /**
   * The owner's offer of the Profile to the host, taken to the status by the share's own writes.
   */
  private String shareAt(ProfileShareStatus status, Profile profile) throws Exception {
    var shareId = offer(owner, profile, host);
    var id = UUID.fromString(shareId);
    var now = Instant.now();
    Runnable move =
        switch (status) {
          case PENDING -> () -> {};
          case ACTIVE -> () -> shareLifecycle.tryActivatePending(id, now);
          case REJECTED, CANCELED -> () -> shareLifecycle.tryDeclinePending(id, status, now);
          case EXPIRED -> () -> lapse(id, now);
          case INVALIDATED ->
              () ->
                  shareLifecycle.tryInvalidatePending(
                      id, ProfileShareLifecycle.OFFERER_NO_LONGER_AUTHORIZED, now);
          case ENDED ->
              () -> {
                shareLifecycle.tryActivatePending(id, now);
                shareLifecycle.tryEndActive(id, now);
              };
        };
    transactionTemplate.executeWithoutResult(_ -> move.run());
    return shareId;
  }

  // An offer past its expiry reads as EXPIRED before the sweep stores it.
  private void lapse(UUID shareId, Instant now) {
    var lapsed = shareRepository.findById(shareId).orElseThrow();
    lapsed.setExpiresAt(now.minusSeconds(1));
    shareRepository.saveAndFlush(lapsed);
  }

  // The change the share's last write announced, as a subscriber would be handed it.
  private ProfileShareChangedEvent changeOf(UUID shareId) {
    var share = shareRepository.findById(shareId).orElseThrow();
    return new ProfileShareChangedEvent(ChangedShare.of(share), share.getStatus().isLive());
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
}
