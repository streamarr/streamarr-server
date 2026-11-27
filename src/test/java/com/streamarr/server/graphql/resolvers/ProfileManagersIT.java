package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A Profile's managers, as the Managers section of its page lists them. */
@Tag("IntegrationTest")
@DisplayName("Profile Managers Integration Tests")
class ProfileManagersIT extends AbstractIntegrationTest {

  private static final String PROFILE_MANAGERS =
      """
      query { profileManagers(profileId: "%s") {
        edges { node { accountId displayName source reader } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity alex;
  private TestIdentity zed;
  private TestIdentity abe;

  @BeforeEach
  void setUp() {
    alex = track(authTestSupport.createIdentity());
    zed = track(authTestSupport.createIdentity());
    abe = track(authTestSupport.createIdentity());
    cast.rename(alex, "Zoe");
    cast.rename(zed, "Zed");
    cast.rename(abe, "Abe");
    cast.grant(zed, alex.profile());
    cast.grant(abe, alex.profile());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should list the own Account first as derived, then the direct managers by name, when the"
          + " sovereign Account reads")
  void shouldListTheOwnAccountFirstAsDerivedThenDirectManagersByNameWhenTheSovereignAccountReads()
      throws Exception {
    var managers = managers(alex, alex.profile());

    assertThat(managers)
        .extracting(manager -> manager.path("accountId").asString())
        .containsExactly(idOf(alex), idOf(abe), idOf(zed));
    assertThat(managers)
        .extracting(manager -> manager.path("source").asString())
        .containsExactly("DERIVED", "DIRECT", "DIRECT");
    assertThat(managers)
        .extracting(manager -> manager.path("reader").asBoolean())
        .containsExactly(true, false, false);
    assertThat(managers.get(1).path("displayName").asString()).isEqualTo("Abe");
  }

  @Test
  @DisplayName("Should list the same managers, none of them the reader, when a ServerAdmin reads")
  void shouldListTheSameManagersNoneOfThemTheReaderWhenAServerAdminReads() throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    var managers = managers(serverAdmin, alex.profile());

    assertThat(managers)
        .extracting(manager -> manager.path("accountId").asString())
        .containsExactly(idOf(alex), idOf(abe), idOf(zed));
    assertThat(managers).hasSize(3).noneMatch(manager -> manager.path("reader").asBoolean());
  }

  @Test
  @DisplayName(
      "Should leave the own Account out and show it nothing when its Personal Profile is"
          + " supervised")
  void shouldLeaveTheOwnAccountOutAndShowItNothingWhenItsPersonalProfileIsSupervised()
      throws Exception {
    var supervised = track(cast.supervisedMemberOf(alex));

    assertThat(managers(alex, supervised.profile()))
        .extracting(manager -> manager.path("accountId").asString())
        .containsExactly(idOf(alex));
    assertThat(managers(alex, supervised.profile()).getFirst().path("source").asString())
        .isEqualTo("DIRECT");
    assertThat(managers(supervised, supervised.profile())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should give an empty list when a HouseholdAdmin who only supervises the Profile where it is"
          + " shared asks")
  void shouldGiveAnEmptyListWhenAHouseholdAdminWhoOnlySupervisesTheProfileAsks() throws Exception {
    var kid = cast.profileManagedBy(alex, ProfileFixture.kidProfileBuilder().name("Sam"));
    var cabin = track(authTestSupport.createIdentity());
    cast.makeAvailable(kid, cabin);

    assertThat(managers(cabin, kid)).isEmpty();
  }

  @Test
  @DisplayName("Should give an empty list when a device-bound session of a manager asks")
  void shouldGiveAnEmptyListWhenADeviceBoundSessionOfAManagerAsks() throws Exception {
    assertThat(managers(authTestSupport.deviceBoundProfileBearer(alex), alex.profile().getId()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should give an empty list when the Profile does not exist")
  void shouldGiveAnEmptyListWhenTheProfileDoesNotExist() throws Exception {
    assertThat(managers(authTestSupport.accountBearer(alex), UUID.randomUUID())).isEmpty();
  }

  @Test
  @DisplayName("Should read a Profile's managers in the same number of statements when it has more")
  void shouldReadAProfilesManagersInTheSameNumberOfStatementsWhenItHasMore() throws Exception {
    // The first request fills caches that later requests skip.
    managers(alex, alex.profile());
    var forThreeManagers = statementCount.statementsWhile(() -> managers(alex, alex.profile()));

    cast.grant(track(authTestSupport.createIdentity()), alex.profile());
    cast.grant(track(authTestSupport.createIdentity()), alex.profile());

    assertThat(managers(alex, alex.profile())).hasSize(5);
    assertThat(statementCount.statementsWhile(() -> managers(alex, alex.profile())))
        .isEqualTo(forThreeManagers);
  }

  private List<JsonNode> managers(TestIdentity reader, Profile profile) throws Exception {
    return managers(authTestSupport.accountBearer(reader), profile.getId());
  }

  private List<JsonNode> managers(String bearer, UUID profileId) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(bearer, PROFILE_MANAGERS.formatted(profileId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var nodes = new ArrayList<JsonNode>();
    objectMapper
        .readTree(body)
        .path("data")
        .path("profileManagers")
        .path("edges")
        .forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  private static String idOf(TestIdentity identity) {
    return identity.account().getId().toString();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
