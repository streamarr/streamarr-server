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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A Household's Profiles, as Household settings lists them for each kind of reader. */
@Tag("IntegrationTest")
@DisplayName("Household Profiles Integration Tests")
class HouseholdProfilesIT extends AbstractIntegrationTest {

  private static final String HOUSEHOLD_PROFILES =
      """
      query { householdProfiles(householdId: "%s") {
        edges { node { visitor locked available
          profile { id name kind pinConfigured readerAuthority household { id name } } } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;

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
  @DisplayName(
      "Should list every available Profile with its lock and visitors when a HouseholdAdmin reads"
          + " its Household")
  void shouldListEveryAvailableProfileWithItsLockAndVisitorsWhenAHouseholdAdminReadsItsHousehold()
      throws Exception {
    var alex = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().name("Alex"));
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Mara"));
    cast.makeAvailable(mara, smith);
    var theo =
        cast.unavailableProfileManagedBy(
            smith, ProfileFixture.defaultProfileBuilder().name("Theo"));
    var otherAdmin = cast.secondAdminOf(smith);
    cast.unavailableProfileManagedBy(otherAdmin, ProfileFixture.defaultProfileBuilder());

    var rows = householdProfiles(smith, smith);

    assertThat(rows)
        .extracting(row -> row.path("profile").path("id").asString())
        .containsExactlyInAnyOrder(
            idOf(alex), idOf(mara), idOf(otherAdmin.profile()), idOf(smith.profile()), idOf(theo));
    assertThat(rows)
        .extracting(row -> row.path("profile").path("name").asString().toLowerCase())
        .isSorted();
    var alexRow = rowOf(rows, alex);
    assertThat(alexRow.path("visitor").asBoolean()).isFalse();
    assertThat(alexRow.path("locked").asBoolean()).isFalse();
    assertThat(alexRow.path("profile").path("readerAuthority").asString()).isEqualTo("MANAGES");
    var maraRow = rowOf(rows, mara);
    assertThat(maraRow.path("visitor").asBoolean()).isTrue();
    assertThat(maraRow.path("locked").asBoolean()).isTrue();
    assertThat(maraRow.path("profile").path("household").isNull()).isTrue();
    assertThat(maraRow.path("profile").path("readerAuthority").isNull()).isTrue();
    var ownRow = rowOf(rows, smith.profile());
    assertThat(ownRow.path("locked").asBoolean()).isTrue();
    assertThat(ownRow.path("profile").path("household").path("name").asString())
        .isEqualTo(smith.household().getName());
    var theoRow = rowOf(rows, theo);
    assertThat(theoRow.path("visitor").asBoolean()).isFalse();
    assertThat(theoRow.path("locked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "Should list only the Profiles the member manages that belong there when a member who is no"
          + " admin reads the Household")
  void shouldListOnlyTheProfilesTheMemberManagesThatBelongThereWhenAMemberWhoIsNoAdminReadsIt()
      throws Exception {
    var member = authTestSupport.createHouseholdMemberIdentity(smith);
    var alex = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Alex"));
    cast.grant(member, alex);
    cast.makeAvailable(alex, cabin);
    cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Mara"));
    cast.grant(member, mara);

    assertThat(householdProfiles(member, smith))
        .extracting(row -> row.path("profile").path("id").asString())
        .containsExactlyInAnyOrder(idOf(alex), idOf(member.profile()));
    assertThat(householdProfiles(member, cabin))
        .extracting(row -> row.path("profile").path("id").asString())
        .containsExactly(idOf(mara));
  }

  @Test
  @DisplayName(
      "Should say a Profile it manages is not available there when a HouseholdAdmin reads its"
          + " Household")
  void shouldSayAProfileItManagesIsNotAvailableThereWhenAHouseholdAdminReadsItsHousehold()
      throws Exception {
    var alex = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Alex"));
    var mara =
        cast.unavailableProfileManagedBy(
            smith, ProfileFixture.defaultProfileBuilder().name("Mara"));

    var rows = householdProfiles(smith, smith);

    assertThat(rowOf(rows, alex).path("available").asBoolean()).isTrue();
    assertThat(rowOf(rows, mara).path("available").asBoolean()).isFalse();
    assertThat(rowOf(rows, mara).path("locked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "Should list every available Profile when a ServerAdmin reads a Household it does not belong"
          + " to")
  void shouldListEveryAvailableProfileWhenAServerAdminReadsAHouseholdItDoesNotBelongTo()
      throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var alex = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().name("Alex"));
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder().name("Mara"));
    cast.makeAvailable(mara, smith);
    cast.unavailableProfileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Theo"));

    assertThat(householdProfiles(serverAdmin, smith))
        .extracting(row -> row.path("profile").path("id").asString())
        .containsExactlyInAnyOrder(idOf(alex), idOf(mara), idOf(smith.profile()));
  }

  @Test
  @DisplayName("Should give an empty list when the reader's own Personal Profile is supervised")
  void shouldGiveAnEmptyListWhenTheReadersOwnPersonalProfileIsSupervised() throws Exception {
    var supervised = cast.supervisedMemberOf(smith);

    assertThat(householdProfiles(supervised, smith)).isEmpty();
  }

  @Test
  @DisplayName("Should give an empty list when a device-bound session reads the Household")
  void shouldGiveAnEmptyListWhenADeviceBoundSessionReadsTheHousehold() throws Exception {
    assertThat(householdProfiles(authTestSupport.deviceBoundProfileBearer(smith), smith)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read a Household's Profiles in the same number of statements when it holds more")
  void shouldReadAHouseholdsProfilesInTheSameNumberOfStatementsWhenItHoldsMore() throws Exception {
    cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
    cast.makeAvailable(cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    // The first request fills caches that later requests skip.
    householdProfiles(smith, smith);
    var forThreeProfiles = statementCount.statementsWhile(() -> householdProfiles(smith, smith));

    cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().maximumAllowedRatingAge(7));
    cast.unavailableProfileManagedBy(smith, ProfileFixture.defaultProfileBuilder());
    cast.makeAvailable(cast.profileManagedBy(cabin, ProfileFixture.kidProfileBuilder()), smith);
    cast.supervisedMemberOf(smith);

    assertThat(householdProfiles(smith, smith)).hasSize(7);
    assertThat(statementCount.statementsWhile(() -> householdProfiles(smith, smith)))
        .isEqualTo(forThreeProfiles);
  }

  private List<JsonNode> householdProfiles(TestIdentity reader, TestIdentity household)
      throws Exception {
    return householdProfiles(authTestSupport.accountBearer(reader), household);
  }

  private List<JsonNode> householdProfiles(String bearer, TestIdentity household) throws Exception {
    var body =
        mockMvc
            .perform(
                graphqlRequest(bearer, HOUSEHOLD_PROFILES.formatted(household.household().getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var nodes = new ArrayList<JsonNode>();
    objectMapper
        .readTree(body)
        .path("data")
        .path("householdProfiles")
        .path("edges")
        .forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  private static JsonNode rowOf(List<JsonNode> rows, Profile profile) {
    return rows.stream()
        .filter(row -> row.path("profile").path("id").asString().equals(idOf(profile)))
        .findFirst()
        .orElseThrow();
  }

  private static String idOf(Profile profile) {
    return profile.getId().toString();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
