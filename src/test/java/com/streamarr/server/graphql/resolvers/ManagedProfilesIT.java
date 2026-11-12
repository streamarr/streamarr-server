package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The Profiles an Account manages, across Households, for Household settings. */
@Tag("IntegrationTest")
@DisplayName("Managed Profiles Integration Tests")
class ManagedProfilesIT extends AbstractIntegrationTest {

  private static final String MANAGED_PROFILES =
      """
      query { me { managedProfiles {
        edges { node { available
          profile { id name kind linked readerAuthority household { id name } } } } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity cabin;

  @BeforeEach
  void setUp() {
    sam = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should list the own Personal Profile and each directly managed Profile when the Account"
          + " manages Profiles in two Households")
  void
      shouldListTheOwnPersonalProfileAndEachDirectlyManagedProfileWhenTheAccountManagesProfilesInTwoHouseholds()
          throws Exception {
    var alex = cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Alex"));
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Mara"));
    cast.grant(sam, mara);
    cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Theo"));
    authTestSupport.createHouseholdMemberIdentity(sam);

    var nodes = managedProfiles(sam);

    assertThat(nodes)
        .extracting(node -> node.path("id").asString())
        .containsExactlyInAnyOrder(
            sam.profile().getId().toString(), alex.getId().toString(), mara.getId().toString());
    assertThat(nodes).extracting(node -> node.path("name").asString().toLowerCase()).isSorted();
    assertThat(nodes)
        .hasSize(3)
        .allSatisfy(
            node -> assertThat(node.path("readerAuthority").asString()).isEqualTo("MANAGES"));
    assertThat(nodeFor(nodes, mara.getId().toString()).path("household").path("name").asString())
        .isEqualTo(cabin.household().getName());
    assertThat(nodeFor(nodes, alex.getId().toString()).path("household").path("name").asString())
        .isEqualTo(sam.household().getName());
    assertThat(nodeFor(nodes, sam.profile().getId().toString()).path("linked").asBoolean())
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should say whether each Profile is available where it belongs when its manager reads them")
  void shouldSayWhetherEachProfileIsAvailableWhereItBelongsWhenItsManagerReadsThem()
      throws Exception {
    var alex = cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Alex"));
    var mara =
        cast.unavailableProfileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Mara"));

    var rows = managedRows(authTestSupport.accountBearer(sam));

    assertThat(rows)
        .extracting(
            row -> row.path("profile").path("id").asString(),
            row -> row.path("available").asBoolean())
        .containsExactlyInAnyOrder(
            tuple(sam.profile().getId().toString(), true),
            tuple(alex.getId().toString(), true),
            tuple(mara.getId().toString(), false));
  }

  @Test
  @DisplayName("Should list only the Profiles it manages when a ServerAdmin reads")
  void shouldListOnlyTheProfilesItManagesWhenAServerAdminReads() throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Alex"));
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Mara"));
    cast.grant(serverAdmin, mara);

    assertThat(managedProfiles(serverAdmin))
        .extracting(node -> node.path("id").asString())
        .containsExactlyInAnyOrder(
            serverAdmin.profile().getId().toString(), mara.getId().toString());
  }

  @Test
  @DisplayName("Should leave out a Kid it only supervises when a HouseholdAdmin reads")
  void shouldLeaveOutAKidItOnlySupervisesWhenAHouseholdAdminReads() throws Exception {
    var otherAdmin = cast.secondAdminOf(sam);
    cast.profileManagedBy(otherAdmin, ProfileFixture.kidProfileBuilder().name("Alex"));

    assertThat(managedProfiles(sam))
        .extracting(node -> node.path("id").asString())
        .containsExactly(sam.profile().getId().toString());
  }

  @Test
  @DisplayName("Should list nothing when the Account's own Personal Profile is supervised")
  void shouldListNothingWhenTheAccountsOwnPersonalProfileIsSupervised() throws Exception {
    var supervised = cast.supervisedMemberOf(sam);

    assertThat(managedProfiles(supervised)).isEmpty();
  }

  @Test
  @DisplayName("Should give an empty list when a device-bound session asks")
  void shouldGiveAnEmptyListWhenADeviceBoundSessionAsks() throws Exception {
    cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Alex"));

    assertThat(managedProfiles(authTestSupport.deviceBoundProfileBearer(sam))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read managed Profiles with their Households in the same number of statements when"
          + " the Account manages more")
  void shouldReadManagedProfilesWithTheirHouseholdsInTheSameNumberOfStatementsWhenItManagesMore()
      throws Exception {
    cast.grant(sam, cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()));
    // The first request fills caches that later requests skip.
    managedProfiles(sam);
    var forTwoProfiles = statementCount.statementsWhile(() -> managedProfiles(sam));

    var lodge = track(authTestSupport.createIdentity());
    cast.grant(sam, cast.profileManagedBy(lodge, ProfileFixture.kidProfileBuilder()));
    cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder());

    assertThat(managedProfiles(sam)).hasSize(4);
    assertThat(statementCount.statementsWhile(() -> managedProfiles(sam)))
        .isEqualTo(forTwoProfiles);
  }

  private List<JsonNode> managedProfiles(TestIdentity reader) throws Exception {
    return managedProfiles(authTestSupport.accountBearer(reader));
  }

  private List<JsonNode> managedProfiles(String bearer) throws Exception {
    return managedRows(bearer).stream().map(row -> row.path("profile")).toList();
  }

  private List<JsonNode> managedRows(String bearer) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(bearer, MANAGED_PROFILES))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var nodes = new ArrayList<JsonNode>();
    objectMapper
        .readTree(body)
        .path("data")
        .path("me")
        .path("managedProfiles")
        .path("edges")
        .forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  private static JsonNode nodeFor(List<JsonNode> nodes, String id) {
    return nodes.stream()
        .filter(node -> node.path("id").asString().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
