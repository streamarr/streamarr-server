package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.language.Language;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.domain.language.SubtitlePreferenceMode;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * A Profile's own language preferences on the Profile's page, which its managers read and change
 * without selecting the Profile (ADR 0024 §Read authorization: the legitimately selected Account,
 * ProfileManagers and ServerAdmin read them; §ProfileManager: a manager edits the Profile).
 */
@Tag("IntegrationTest")
@DisplayName("Profile Language Preferences Integration Tests")
class ProfileLanguagePreferencesIT extends AbstractIntegrationTest {

  private static final String PROFILE_ADMINISTRATION =
      """
      query { profileAdministration(profileId: "%s") { id
        languagePreferences { audioLanguages subtitleLanguage subtitleMode } } }
      """;

  private static final String HOUSEHOLD_PROFILES =
      """
      query { householdProfiles(householdId: "%s") { edges { node {
        profile { id languagePreferences { audioLanguages subtitleLanguage subtitleMode } } } } } }
      """;

  private static final String MANAGED_PROFILES =
      """
      query { me { managedProfiles { edges { node {
        profile { id languagePreferences { audioLanguages subtitleLanguage subtitleMode } } } } } } }
      """;

  private static final String SET_PROFILE_LANGUAGE_PREFERENCES =
      """
      mutation { setProfileLanguagePreferences(input: {profileId: "%s", audioLanguages: [%s],
          subtitleMode: OFF}) {
        languagePreferences { audioLanguages subtitleLanguage subtitleMode }
        userErrors { __typename ... on MutationError { message }
          ... on InputMutationError { inputPath }
          ... on TooManyAudioLanguagesError { maximum }
          ... on RepeatedAudioLanguageError { language } } } }
      """;

  private static final String ME_LANGUAGE_PREFERENCES =
      """
      query { me { languagePreferences { audioLanguages subtitleLanguage subtitleMode } } }
      """;

  private static final String PROFILE_NOT_FOUND =
      """
      [{"__typename": "ProfileNotFoundError", "message": "No such Profile.",
        "inputPath": ["profileId"]}]
      """;

  private static final String INVITED_EMAIL = "ana@example.test";

  private static final ProfileLanguagePreferences STORED =
      ProfileLanguagePreferences.builder()
          .audioLanguages(List.of(Language.FR, Language.JA))
          .subtitleLanguage(Optional.of(Language.EN))
          .subtitleMode(SubtitlePreferenceMode.ALWAYS)
          .build();

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;
  @Autowired private LanguagePreferenceRepository languagePreferenceRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;
  private Profile kid;

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
    kid = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
    store(kid);
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /** A reader that may read the Profile's preferences, and the Profile it reads. */
  enum Reader {
    DIRECT_MANAGER_THAT_HAS_NOT_SELECTED_IT,
    SELF_MANAGED_ACCOUNT_READING_ITS_OWN_PROFILE,
    SERVER_ADMIN_THAT_DOES_NOT_MANAGE_IT,
    HOUSEHOLD_ADMIN_THAT_HAS_IT_SELECTED
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Reader.class)
  @DisplayName("Should show the preferences when the reader may read them")
  void shouldShowPreferencesWhenReaderMayReadThem(Reader reader) throws Exception {
    var reading = readingOf(reader);

    var profile = profileAdministration(reading.bearer(), reading.profile());

    assertThat(profile.path("id").asString()).isEqualTo(idOf(reading.profile()));
    assertStored(profile.path("languagePreferences"));
  }

  private Reading readingOf(Reader reader) {
    return switch (reader) {
      case DIRECT_MANAGER_THAT_HAS_NOT_SELECTED_IT -> {
        cast.grant(cabin, kid);
        yield new Reading(authTestSupport.accountBearer(cabin), kid);
      }
      case SELF_MANAGED_ACCOUNT_READING_ITS_OWN_PROFILE -> {
        store(smith.profile());
        yield new Reading(authTestSupport.accountBearer(smith), smith.profile());
      }
      case SERVER_ADMIN_THAT_DOES_NOT_MANAGE_IT ->
          new Reading(
              authTestSupport.accountBearer(track(authTestSupport.createAdminIdentity())), kid);
      case HOUSEHOLD_ADMIN_THAT_HAS_IT_SELECTED ->
          new Reading(authTestSupport.profileBearer(cast.secondAdminOf(smith), kid), kid);
    };
  }

  /** A reader that may not read the Profile's preferences, and whether it reads the Profile. */
  enum Outsider {
    HOUSEHOLD_ADMIN_THAT_ONLY_HOSTS_AN_UNRESTRICTED_VISITOR(true),
    HOUSEHOLD_ADMIN_THAT_ONLY_SUPERVISES_A_RESTRICTED_PROFILE(true),
    INVITED_ACCOUNT_THAT_HAS_NOT_ACCEPTED(false),
    TARGET_HOUSEHOLD_ADMIN_OF_A_WAITING_OFFER(false),
    MEMBER_THAT_HAS_IT_SELECTED(false),
    DEVICE_BOUND_SESSION_OF_ITS_MANAGER(false),
    DISABLED_MANAGER(false);

    private final boolean readsTheProfile;

    Outsider(boolean readsTheProfile) {
      this.readsTheProfile = readsTheProfile;
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Outsider.class)
  @DisplayName("Should give no preferences when the reader may not read them")
  void shouldGiveNoPreferencesWhenReaderMayNotReadThem(Outsider outsider) throws Exception {
    var reading = readingOf(outsider);

    var profile = profileAdministration(reading.bearer(), reading.profile());

    assertThat(profile.isNull()).as("the Profile is hidden").isEqualTo(!outsider.readsTheProfile);
    assertThat(profile.path("languagePreferences").isObject()).as("preferences shown").isFalse();
  }

  private Reading readingOf(Outsider outsider) throws Exception {
    return switch (outsider) {
      case HOUSEHOLD_ADMIN_THAT_ONLY_HOSTS_AN_UNRESTRICTED_VISITOR -> {
        var visitor = cast.profileManagedBy(smith, ProfileFixture.defaultProfileBuilder());
        store(visitor);
        cast.makeAvailable(visitor, cabin);
        yield new Reading(authTestSupport.accountBearer(cabin), visitor);
      }
      case HOUSEHOLD_ADMIN_THAT_ONLY_SUPERVISES_A_RESTRICTED_PROFILE ->
          new Reading(authTestSupport.accountBearer(cast.secondAdminOf(smith)), kid);
      case INVITED_ACCOUNT_THAT_HAS_NOT_ACCEPTED -> {
        var invited = track(authTestSupport.createIdentityWithEmail(INVITED_EMAIL));
        inviteToManageKid(INVITED_EMAIL);
        yield new Reading(authTestSupport.accountBearer(invited), kid);
      }
      case TARGET_HOUSEHOLD_ADMIN_OF_A_WAITING_OFFER -> {
        offerInto(cabin);
        yield new Reading(authTestSupport.accountBearer(cabin), kid);
      }
      case MEMBER_THAT_HAS_IT_SELECTED ->
          new Reading(
              authTestSupport.profileBearer(
                  authTestSupport.createHouseholdMemberIdentity(smith), kid),
              kid);
      case DEVICE_BOUND_SESSION_OF_ITS_MANAGER ->
          new Reading(authTestSupport.deviceBoundProfileBearer(smith), kid);
      case DISABLED_MANAGER -> {
        cast.enable(smith, false);
        yield new Reading(authTestSupport.accountBearer(smith), kid);
      }
    };
  }

  @Test
  @DisplayName(
      "Should read the preferences of every row in the same number of statements when the"
          + " Household holds more Profiles")
  void shouldReadPreferencesOfEveryRowInSameNumberOfStatementsWhenHouseholdHoldsMoreProfiles()
      throws Exception {
    store(smith.profile());
    cast.makeAvailable(cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder()), smith);
    authTestSupport.createHouseholdMemberIdentity(smith);
    // The first request fills caches that later requests skip.
    householdProfiles(smith);
    var forFourProfiles = statementCount.statementsWhile(() -> householdProfiles(smith));

    var ceilinged =
        cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder().maximumAllowedRatingAge(7));
    store(ceilinged);
    cast.makeAvailable(cast.profileManagedBy(cabin, ProfileFixture.kidProfileBuilder()), smith);
    authTestSupport.createHouseholdMemberIdentity(smith);

    var rows = householdProfiles(smith);
    assertThat(rows).hasSize(7);
    assertThat(rows)
        .filteredOn(row -> !row.path("languagePreferences").isNull())
        .extracting(row -> row.path("id").asString())
        .containsExactlyInAnyOrder(idOf(smith.profile()), idOf(kid), idOf(ceilinged));
    assertThat(statementCount.statementsWhile(() -> householdProfiles(smith)))
        .isEqualTo(forFourProfiles);
  }

  @Test
  @DisplayName(
      "Should read the preferences of every managed Profile in the same number of statements when"
          + " the manager manages more")
  void shouldReadPreferencesOfEveryManagedProfileInSameNumberOfStatementsWhenManagerManagesMore()
      throws Exception {
    store(smith.profile());
    var mara = cast.profileManagedBy(cabin, ProfileFixture.defaultProfileBuilder());
    store(mara);
    cast.grant(smith, mara);
    // The first request fills caches that later requests skip.
    managedProfiles(smith);
    var forThreeProfiles = statementCount.statementsWhile(() -> managedProfiles(smith));

    var lodge = track(authTestSupport.createIdentity());
    var theo = cast.profileManagedBy(lodge, ProfileFixture.kidProfileBuilder());
    store(theo);
    cast.grant(smith, theo);
    store(cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder()));

    var rows = managedProfiles(smith);
    assertThat(rows).hasSize(5);
    assertThat(rows).allSatisfy(row -> assertStored(row.path("languagePreferences")));
    assertThat(statementCount.statementsWhile(() -> managedProfiles(smith)))
        .isEqualTo(forThreeProfiles);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = Reader.class,
      names = {
        "DIRECT_MANAGER_THAT_HAS_NOT_SELECTED_IT",
        "SELF_MANAGED_ACCOUNT_READING_ITS_OWN_PROFILE"
      })
  @DisplayName("Should store the preferences when a manager changes a Profile it has not selected")
  void shouldStorePreferencesWhenManagerChangesProfileItHasNotSelected(Reader manager)
      throws Exception {
    var reading = readingOf(manager);

    var answer = changePreferences(reading.bearer(), idOf(reading.profile()), "DE, EN");

    assertThat(answer.path("userErrors").values()).isEmpty();
    assertChanged(answer.path("languagePreferences"));
    assertChanged(
        profileAdministration(reading.bearer(), reading.profile()).path("languagePreferences"));
    assertChanged(
        data(authTestSupport.profileBearer(smith, reading.profile()), ME_LANGUAGE_PREFERENCES)
            .path("me")
            .path("languagePreferences"));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = Reader.class,
      names = {"SERVER_ADMIN_THAT_DOES_NOT_MANAGE_IT", "HOUSEHOLD_ADMIN_THAT_HAS_IT_SELECTED"})
  @DisplayName(
      "Should forbid the change when a reader of the preferences does not manage the Profile")
  void shouldForbidChangeWhenReaderOfPreferencesDoesNotManageProfile(Reader reader)
      throws Exception {
    assertForbidden(readingOf(reader));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = Outsider.class,
      names = {
        "HOUSEHOLD_ADMIN_THAT_ONLY_HOSTS_AN_UNRESTRICTED_VISITOR",
        "HOUSEHOLD_ADMIN_THAT_ONLY_SUPERVISES_A_RESTRICTED_PROFILE"
      })
  @DisplayName(
      "Should forbid the change when a HouseholdAdmin only hosts or supervises the Profile")
  void shouldForbidChangeWhenHouseholdAdminOnlyHostsOrSupervisesProfile(Outsider admin)
      throws Exception {
    assertForbidden(readingOf(admin));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = Outsider.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {
        "HOUSEHOLD_ADMIN_THAT_ONLY_HOSTS_AN_UNRESTRICTED_VISITOR",
        "HOUSEHOLD_ADMIN_THAT_ONLY_SUPERVISES_A_RESTRICTED_PROFILE"
      })
  @DisplayName("Should answer that no such Profile exists when the caller may not read it")
  void shouldAnswerProfileNotFoundWhenCallerMayNotReadProfile(Outsider outsider) throws Exception {
    var reading = readingOf(outsider);

    var answer = changePreferences(reading.bearer(), idOf(reading.profile()), "DE, EN");

    assertThat(answer.path("languagePreferences").isNull()).isTrue();
    assertThat(answer.path("userErrors")).isEqualTo(objectMapper.readTree(PROFILE_NOT_FOUND));
    assertThat(storedPreferencesOf(reading.profile())).isEqualTo(STORED);
  }

  @Test
  @DisplayName("Should answer that no such Profile exists when no Profile has the id")
  void shouldAnswerProfileNotFoundWhenNoProfileHasTheId() throws Exception {
    var answer =
        changePreferences(
            authTestSupport.accountBearer(smith), UUID.randomUUID().toString(), "DE, EN");

    assertThat(answer.path("languagePreferences").isNull()).isTrue();
    assertThat(answer.path("userErrors")).isEqualTo(objectMapper.readTree(PROFILE_NOT_FOUND));
  }

  @Test
  @DisplayName("Should refuse the audio languages at their input path when a manager names six")
  void shouldRefuseAudioLanguagesAtTheirInputPathWhenManagerNamesSix() throws Exception {
    var answer =
        changePreferences(
            authTestSupport.accountBearer(smith), idOf(kid), "EN, FR, DE, ES, IT, JA");

    assertThat(answer.path("languagePreferences").isNull()).isTrue();
    assertThat(answer.path("userErrors"))
        .isEqualTo(
            objectMapper.readTree(
                """
                [{"__typename": "TooManyAudioLanguagesError",
                  "message": "Choose at most 5 audio languages.",
                  "inputPath": ["audioLanguages"], "maximum": 5}]
                """));
    assertThat(storedPreferencesOf(kid)).isEqualTo(STORED);
  }

  @Test
  @DisplayName(
      "Should refuse the repeated audio language at its input path when a manager names one twice")
  void shouldRefuseRepeatedAudioLanguageAtItsInputPathWhenManagerNamesOneTwice() throws Exception {
    var answer = changePreferences(authTestSupport.accountBearer(smith), idOf(kid), "EN, FR, EN");

    assertThat(answer.path("languagePreferences").isNull()).isTrue();
    assertThat(answer.path("userErrors"))
        .isEqualTo(
            objectMapper.readTree(
                """
                [{"__typename": "RepeatedAudioLanguageError",
                  "message": "Choose each audio language once.",
                  "inputPath": ["audioLanguages", "2"], "language": "EN"}]
                """));
    assertThat(storedPreferencesOf(kid)).isEqualTo(STORED);
  }

  private void assertForbidden(Reading reading) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                reading.bearer(),
                SET_PROFILE_LANGUAGE_PREFERENCES.formatted(idOf(reading.profile()), "DE, EN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.data.setProfileLanguagePreferences").doesNotExist());

    assertThat(storedPreferencesOf(reading.profile())).isEqualTo(STORED);
  }

  private JsonNode changePreferences(String bearer, String profileId, String audioLanguages)
      throws Exception {
    return data(bearer, SET_PROFILE_LANGUAGE_PREFERENCES.formatted(profileId, audioLanguages))
        .path("setProfileLanguagePreferences");
  }

  private static void assertChanged(JsonNode preferences) {
    assertThat(preferences.path("audioLanguages").values())
        .extracting(JsonNode::asString)
        .containsExactly("DE", "EN");
    assertThat(preferences.path("subtitleLanguage").isNull()).isTrue();
    assertThat(preferences.path("subtitleMode").asString()).isEqualTo("OFF");
  }

  private ProfileLanguagePreferences storedPreferencesOf(Profile profile) {
    return languagePreferenceRepository.findProfilePreferences(profile.getId()).orElseThrow();
  }

  private record Reading(String bearer, Profile profile) {}

  private static void assertStored(JsonNode preferences) {
    assertThat(preferences.path("audioLanguages").values())
        .extracting(JsonNode::asString)
        .containsExactly("FR", "JA");
    assertThat(preferences.path("subtitleLanguage").asString()).isEqualTo("EN");
    assertThat(preferences.path("subtitleMode").asString()).isEqualTo("ALWAYS");
  }

  private void store(Profile profile) {
    Boolean stored =
        transactionTemplate.execute(
            _ -> languagePreferenceRepository.trySetProfilePreferences(profile.getId(), STORED));
    assertThat(stored).isTrue();
  }

  private void inviteToManageKid(String email) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(smith),
                """
                mutation { inviteProfileManager(input: {profileId: "%s", email: "%s"}) {
                  userErrors { __typename } } }
                """
                    .formatted(kid.getId(), email)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.inviteProfileManager.userErrors").isEmpty());
  }

  private void offerInto(TestIdentity household) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(smith),
                """
                mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                  share { status } userErrors { __typename } } }
                """
                    .formatted(kid.getId(), authTestSupport.shareCodeFor(household))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"));
  }

  private JsonNode profileAdministration(String bearer, Profile profile) throws Exception {
    return data(bearer, PROFILE_ADMINISTRATION.formatted(profile.getId()))
        .path("profileAdministration");
  }

  private List<JsonNode> householdProfiles(TestIdentity reader) throws Exception {
    var rows = new ArrayList<JsonNode>();
    data(
            authTestSupport.accountBearer(reader),
            HOUSEHOLD_PROFILES.formatted(reader.household().getId()))
        .path("householdProfiles")
        .path("edges")
        .forEach(edge -> rows.add(edge.path("node").path("profile")));
    return rows;
  }

  private List<JsonNode> managedProfiles(TestIdentity reader) throws Exception {
    var rows = new ArrayList<JsonNode>();
    data(authTestSupport.accountBearer(reader), MANAGED_PROFILES)
        .path("me")
        .path("managedProfiles")
        .path("edges")
        .forEach(edge -> rows.add(edge.path("node").path("profile")));
    return rows;
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
