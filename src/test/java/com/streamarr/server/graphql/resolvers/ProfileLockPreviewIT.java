package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
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

/** The lock warning a Profile's page shows before its kind or content limit changes. */
@Tag("IntegrationTest")
@DisplayName("Profile Lock Preview Integration Tests")
class ProfileLockPreviewIT extends AbstractIntegrationTest {

  private static final String POLICY_CHANGE_PREVIEW =
      """
      query { profilePolicyChangePreview(profileId: "%s", kind: %s, maximumAllowedRatingAge: %s) {
        households { household { id name } profiles { id name } locksThisProfile } } }
      """;

  private static final String CREATION_PREVIEW =
      """
      query { profileCreationPreview(householdId: "%s", kind: %s, maximumAllowedRatingAge: %s) {
        households { household { id name } profiles { id name } locksThisProfile } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileRepository profileRepository;

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
      "Should list the Adults without a PIN and change nothing when a manager previews making an"
          + " Adult a Kid")
  void shouldListTheAdultsWithoutAPinAndChangeNothingWhenAManagerPreviewsMakingAnAdultAKid()
      throws Exception {
    var sam = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Sam"));
    var mara = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Mara"));

    var preview = preview(authTestSupport.accountBearer(smith), sam, ProfileKind.KID, "10");

    var households = preview.path("households");
    assertThat(households).hasSize(1);
    assertThat(households.get(0).path("household").path("name").asString())
        .isEqualTo(smith.household().getName());
    assertThat(households.get(0).path("profiles"))
        .extracting(profile -> profile.path("id").asString())
        .containsExactlyInAnyOrder(idOf(mara), idOf(smith.profile()));
    assertThat(households.get(0).path("locksThisProfile").asBoolean()).isFalse();
    assertThat(profileRepository.findById(sam.getId()).orElseThrow())
        .extracting(Profile::getKind, Profile::getMaximumAllowedRatingAge)
        .containsExactly(ProfileKind.ADULT, null);
  }

  @Test
  @DisplayName(
      "Should list only the Household whose Profile picker each reader may see when a Kid shared"
          + " into another Household would lock in both")
  void shouldListOnlyTheHouseholdWhosePickerEachReaderMaySeeWhenAKidWouldLockInBoth()
      throws Exception {
    var sam =
        cast.profileManagedBy(
            smith, ProfileFixture.kidProfileBuilder().name("Sam").maximumAllowedRatingAge(7));
    cast.makeAvailable(sam, cabin);
    cast.profileManagedBy(
        smith, ProfileFixture.kidProfileBuilder().name("Mara").maximumAllowedRatingAge(10));
    cast.profileManagedBy(
        cabin, ProfileFixture.kidProfileBuilder().name("Theo").maximumAllowedRatingAge(10));

    var managers = preview(authTestSupport.accountBearer(smith), sam, ProfileKind.KID, "13");
    var hosts = preview(authTestSupport.accountBearer(cabin), sam, ProfileKind.KID, "13");

    assertThat(managers.path("households"))
        .singleElement()
        .satisfies(
            household -> {
              assertThat(household.path("household").path("id").asString())
                  .isEqualTo(smith.household().getId().toString());
              assertThat(household.path("locksThisProfile").asBoolean()).isTrue();
            });
    assertThat(hosts.path("households"))
        .singleElement()
        .satisfies(
            household -> {
              assertThat(household.path("household").path("id").asString())
                  .isEqualTo(cabin.household().getId().toString());
              assertThat(household.path("locksThisProfile").asBoolean()).isTrue();
            });
  }

  @Test
  @DisplayName(
      "Should answer null when a member who is no admin asks about a Profile it cannot see")
  void shouldAnswerNullWhenAMemberWhoIsNoAdminAsksAboutAProfileItCannotSee() throws Exception {
    var sam = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Sam"));
    var member = authTestSupport.createHouseholdMemberIdentity(smith);

    assertThat(preview(authTestSupport.accountBearer(member), sam, ProfileKind.KID, "10").isNull())
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should answer null when a member previews making its own Personal Profile a Kid, which only"
          + " a ServerAdmin may do")
  void shouldAnswerNullWhenAMemberPreviewsAChangeOnlyAServerAdminMayMake() throws Exception {
    cast.profileManagedBy(
        smith, ProfileFixture.kidProfileBuilder().name("Kit").maximumAllowedRatingAge(12));
    var pat = track(authTestSupport.createHouseholdMemberIdentity(smith));

    for (var ceiling : List.of("7", "12", "16")) {
      assertThat(
              preview(authTestSupport.accountBearer(pat), pat.profile(), ProfileKind.KID, ceiling)
                  .isNull())
          .isTrue();
    }
  }

  @Test
  @DisplayName("Should answer null when a manager previews a negative content ceiling")
  void shouldAnswerNullWhenAManagerPreviewsANegativeContentCeiling() throws Exception {
    var sam = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().name("Sam"));

    assertThat(preview(authTestSupport.accountBearer(smith), sam, ProfileKind.KID, "-1").isNull())
        .isTrue();
  }

  @Test
  @DisplayName("Should answer null when a device-bound session of the manager asks")
  void shouldAnswerNullWhenADeviceBoundSessionOfTheManagerAsks() throws Exception {
    var sam = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Sam"));

    assertThat(
            preview(authTestSupport.deviceBoundProfileBearer(smith), sam, ProfileKind.KID, "10")
                .isNull())
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should list the Adults without a PIN when a HouseholdAdmin previews adding a Kid to its"
          + " Household")
  void shouldListTheAdultsWithoutAPinWhenAHouseholdAdminPreviewsAddingAKidToItsHousehold()
      throws Exception {
    var mara = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder().name("Mara"));

    var preview = creationPreview(authTestSupport.accountBearer(smith), ProfileKind.KID, "10");

    assertThat(preview.path("households"))
        .singleElement()
        .satisfies(
            household -> {
              assertThat(household.path("household").path("name").asString())
                  .isEqualTo(smith.household().getName());
              assertThat(household.path("profiles"))
                  .extracting(profile -> profile.path("id").asString())
                  .containsExactlyInAnyOrder(idOf(mara), idOf(smith.profile()));
              assertThat(household.path("locksThisProfile").asBoolean()).isFalse();
            });
  }

  @Test
  @DisplayName(
      "Should say the new Profile would lock when a HouseholdAdmin previews adding an Adult where"
          + " a Kid lives")
  void shouldSayTheNewProfileWouldLockWhenAHouseholdAdminPreviewsAddingAnAdultWhereAKidLives()
      throws Exception {
    cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().name("Sam"));

    var preview = creationPreview(authTestSupport.accountBearer(smith), ProfileKind.ADULT, "null");

    assertThat(preview.path("households"))
        .singleElement()
        .satisfies(
            household -> {
              assertThat(household.path("profiles")).isEmpty();
              assertThat(household.path("locksThisProfile").asBoolean()).isTrue();
            });
  }

  @Test
  @DisplayName("Should answer null when a member who is no admin previews adding a Profile")
  void shouldAnswerNullWhenAMemberWhoIsNoAdminPreviewsAddingAProfile() throws Exception {
    var member = authTestSupport.createHouseholdMemberIdentity(smith);

    assertThat(
            creationPreview(authTestSupport.accountBearer(member), ProfileKind.KID, "10").isNull())
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should answer null when a HouseholdAdmin previews adding a negative content ceiling")
  void shouldAnswerNullWhenAHouseholdAdminPreviewsAddingANegativeContentCeiling() throws Exception {
    assertThat(
            creationPreview(authTestSupport.accountBearer(smith), ProfileKind.KID, "-1").isNull())
        .isTrue();
  }

  @Test
  @DisplayName("Should answer null when a device-bound session previews adding a Profile")
  void shouldAnswerNullWhenADeviceBoundSessionPreviewsAddingAProfile() throws Exception {
    assertThat(
            creationPreview(authTestSupport.deviceBoundProfileBearer(smith), ProfileKind.KID, "10")
                .isNull())
        .isTrue();
  }

  private JsonNode preview(String bearer, Profile profile, ProfileKind kind, String ceiling)
      throws Exception {
    return data(bearer, POLICY_CHANGE_PREVIEW.formatted(profile.getId(), kind, ceiling))
        .path("profilePolicyChangePreview");
  }

  private JsonNode creationPreview(String bearer, ProfileKind kind, String ceiling)
      throws Exception {
    return data(bearer, CREATION_PREVIEW.formatted(smith.household().getId(), kind, ceiling))
        .path("profileCreationPreview");
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

  private static String idOf(Profile profile) {
    return profile.getId().toString();
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
