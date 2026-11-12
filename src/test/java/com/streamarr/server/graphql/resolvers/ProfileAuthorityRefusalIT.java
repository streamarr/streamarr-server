package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.language.ProfileLanguagePreferences;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Why a caller may no longer act on a Profile it still reads (ADR 0024 §HouseholdAdmin: a
 * HouseholdAdmin administers a Profile while it is available in the Household and supervises it
 * only while it is restricted; §Read authorization). A caller that may no longer read the Profile
 * is answered as for a Profile that does not exist, whatever it was to the Profile before.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Authority Refusal Integration Tests")
class ProfileAuthorityRefusalIT extends AbstractIntegrationTest {

  private static final String NOT_MANAGER =
      """
      [{"__typename": "ProfileManagersOnlyError",
        "message": "Only the Profile's managers may do this; supervising it does not cover this.",
        "reason": "NOT_MANAGER"}]
      """;

  private static final String NOT_RESTRICTED =
      """
      [{"__typename": "ProfileManagersOnlyError",
        "message": "The Profile is not restricted, so only its managers may do this.",
        "reason": "NOT_RESTRICTED"}]
      """;

  private static final String PROFILE_ADMINISTRATION =
      """
      query { profileAdministration(profileId: "%s") { id restricted readerAuthority } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private LanguagePreferenceRepository languagePreferenceRepository;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;
  private Profile kid;

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
    kid = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /** Every mutation that only some of a Profile's readers may make, and what else it takes. */
  enum ProfileMutation {
    RENAME_PROFILE("renameProfile", ", name: \"Robin\""),
    SET_PROFILE_PICTURE("setProfilePicture", ", picture: \"robin.png\""),
    CHANGE_PROFILE_KIND("changeProfileKind", ", kind: ADULT"),
    SET_PROFILE_MAXIMUM_ALLOWED_RATING_AGE(
        "setProfileMaximumAllowedRatingAge", ", maximumAllowedRatingAge: 12"),
    REMOVE_PROFILE_MAXIMUM_ALLOWED_RATING_AGE("removeProfileMaximumAllowedRatingAge", ""),
    SET_PROFILE_PIN("setProfilePin", ", pin: \"4321\""),
    REMOVE_PROFILE_PIN("removeProfilePin", ""),
    SET_PROFILE_LANGUAGE_PREFERENCES(
        "setProfileLanguagePreferences", ", audioLanguages: [DE], subtitleMode: OFF"),
    DELETE_PROFILE("deleteProfile", "");

    private final String field;
    private final String otherInput;

    ProfileMutation(String field, String otherInput) {
      this.field = field;
      this.otherInput = otherInput;
    }

    String request(UUID profileId) {
      return """
          mutation { %s(input: {profileId: "%s"%s}) { userErrors { __typename
            ... on MutationError { message } ... on InputMutationError { inputPath }
            ... on ProfileManagersOnlyError { reason } } } }
          """
          .formatted(field, profileId, otherInput);
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileMutation.class,
      names = {"CHANGE_PROFILE_KIND", "SET_PROFILE_LANGUAGE_PREFERENCES", "DELETE_PROFILE"})
  @DisplayName(
      "Should say the caller no longer manages the Profile when a ServerAdmin removed its grant and"
          + " it still supervises the Profile")
  void shouldSayCallerNoLongerManagesWhenServerAdminRemovedItsGrantAndItStillSupervisesProfile(
      ProfileMutation mutation) throws Exception {
    cast.grant(cast.secondAdminOf(smith), kid);
    removeGrantAsServerAdmin(smith, kid);
    var before = storedStateOf(kid);

    var answer = answer(authTestSupport.accountBearer(smith), mutation, kid.getId());

    assertUserErrors(answer, mutation, NOT_MANAGER);
    assertThat(storedStateOf(kid)).isEqualTo(before);
    assertThat(profileAdministration(smith, kid).path("readerAuthority").asString())
        .isEqualTo("SUPERVISES");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileMutation.class)
  @DisplayName(
      "Should say the Profile is no longer restricted when a manager lifts its last restriction"
          + " under a supervising admin")
  void shouldSayProfileIsNoLongerRestrictedWhenManagerLiftsLastRestrictionUnderSupervisingAdmin(
      ProfileMutation mutation) throws Exception {
    var supervisor = cast.secondAdminOf(smith);
    perform(
        authTestSupport.freshAccountBearer(smith),
        "changeProfileKind",
        "{profileId: \"%s\", kind: ADULT}".formatted(kid.getId()));
    var before = storedStateOf(kid);

    var answer = answer(authTestSupport.accountBearer(supervisor), mutation, kid.getId());

    assertUserErrors(answer, mutation, NOT_RESTRICTED);
    assertThat(storedStateOf(kid)).isEqualTo(before);
    var stillRead = profileAdministration(supervisor, kid);
    assertThat(stillRead.path("restricted").asBoolean()).isFalse();
    assertThat(stillRead.path("readerAuthority").isNull()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ProfileMutation.class)
  @DisplayName(
      "Should say the Profile is not restricted when its own Account removes a manager that still"
          + " hosts it")
  void shouldSayProfileIsNotRestrictedWhenSovereignRemovesManagerThatStillHostsIt(
      ProfileMutation mutation) throws Exception {
    var robin = authTestSupport.createHouseholdMemberIdentity(smith);
    cast.grant(smith, robin.profile());
    perform(
        authTestSupport.accountBearer(robin),
        "removeProfileManager",
        "{profileId: \"%s\", accountId: \"%s\"}"
            .formatted(robin.profile().getId(), smith.account().getId()));
    var before = storedStateOf(robin.profile());

    var answer = answer(authTestSupport.accountBearer(smith), mutation, robin.profile().getId());

    assertUserErrors(answer, mutation, NOT_RESTRICTED);
    assertThat(storedStateOf(robin.profile())).isEqualTo(before);
  }

  /** A caller that may not read the Profile now, and the Profile it asks about. */
  enum Outsider {
    FORMER_MANAGER,
    DEMOTED_FORMER_SUPERVISOR,
    FORMER_HOST_AFTER_ITS_SHARE_ENDED,
    FORMER_HOST_AFTER_THE_PROFILE_WAS_DELETED,
    NEVER_RELATED_ACCOUNT,
    INVITED_ACCOUNT_THAT_HAS_NOT_ACCEPTED,
    TARGET_HOUSEHOLD_ADMIN_OF_A_WAITING_OFFER,
    MEMBER_THAT_HAS_IT_SELECTED,
    DEVICE_BOUND_SESSION_OF_ITS_MANAGER,
    DISABLED_MANAGER
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Outsider.class)
  @DisplayName(
      "Should give the same answer as for a missing Profile when the caller can no longer read it")
  void shouldGiveSameAnswerAsMissingProfileWhenCallerCanNoLongerReadIt(Outsider outsider)
      throws Exception {
    var asking = askingOf(outsider);
    var missing = UUID.randomUUID();

    for (var mutation : ProfileMutation.values()) {
      var forMissing = answer(asking.bearer(), mutation, missing);
      assertThat(
              forMissing
                  .at("/data/%s/userErrors/0/__typename".formatted(mutation.field))
                  .asString())
          .as("%s answers a missing Profile", mutation)
          .isEqualTo("ProfileNotFoundError");

      assertThat(answer(asking.bearer(), mutation, asking.profileId()))
          .as("%s answers %s", mutation, outsider)
          .isEqualTo(forMissing);
    }
  }

  private Asking askingOf(Outsider outsider) throws Exception {
    return switch (outsider) {
      case FORMER_MANAGER -> {
        cast.grant(cabin, kid);
        perform(
            authTestSupport.accountBearer(cabin),
            "relinquishProfileManagement",
            "{profileId: \"%s\"}".formatted(kid.getId()));
        yield new Asking(authTestSupport.accountBearer(cabin), kid.getId());
      }
      case DEMOTED_FORMER_SUPERVISOR -> {
        var supervisor = cast.secondAdminOf(smith);
        perform(
            authTestSupport.accountBearer(track(authTestSupport.createAdminIdentity())),
            "revokeHouseholdAdmin",
            "{accountId: \"%s\"}".formatted(supervisor.account().getId()));
        yield new Asking(authTestSupport.accountBearer(asItIsNow(supervisor)), kid.getId());
      }
      case FORMER_HOST_AFTER_ITS_SHARE_ENDED ->
          new Asking(authTestSupport.accountBearer(cabin), visitorWhoseShareEnded().getId());
      case FORMER_HOST_AFTER_THE_PROFILE_WAS_DELETED -> {
        var visitor = visitorWhoseShareEnded();
        perform(
            authTestSupport.freshAccountBearer(smith),
            "deleteProfile",
            "{profileId: \"%s\"}".formatted(visitor.getId()));
        yield new Asking(authTestSupport.accountBearer(cabin), visitor.getId());
      }
      case NEVER_RELATED_ACCOUNT ->
          new Asking(
              authTestSupport.accountBearer(track(authTestSupport.createIdentity())), kid.getId());
      case INVITED_ACCOUNT_THAT_HAS_NOT_ACCEPTED -> {
        var invited = track(authTestSupport.createIdentityWithEmail("noor@example.test"));
        perform(
            authTestSupport.accountBearer(smith),
            "inviteProfileManager",
            "{profileId: \"%s\", email: \"noor@example.test\"}".formatted(kid.getId()));
        yield new Asking(authTestSupport.accountBearer(invited), kid.getId());
      }
      case TARGET_HOUSEHOLD_ADMIN_OF_A_WAITING_OFFER -> {
        perform(
            authTestSupport.accountBearer(smith),
            "offerProfileShare",
            "{profileId: \"%s\", shareCode: \"%s\"}"
                .formatted(kid.getId(), authTestSupport.shareCodeFor(cabin)));
        yield new Asking(authTestSupport.accountBearer(cabin), kid.getId());
      }
      case MEMBER_THAT_HAS_IT_SELECTED ->
          new Asking(
              authTestSupport.profileBearer(
                  authTestSupport.createHouseholdMemberIdentity(smith), kid),
              kid.getId());
      case DEVICE_BOUND_SESSION_OF_ITS_MANAGER ->
          new Asking(authTestSupport.deviceBoundProfileBearer(smith), kid.getId());
      case DISABLED_MANAGER -> {
        cast.enable(smith, false);
        yield new Asking(authTestSupport.accountBearer(smith), kid.getId());
      }
    };
  }

  /** An Adult of Smith's that only visited Cabin, whose admin then ended the share. */
  private Profile visitorWhoseShareEnded() throws Exception {
    var visitor = cast.unavailableProfileManagedBy(smith, ProfileFixture.defaultProfileBuilder());
    cast.makeAvailable(visitor, cabin);
    var share =
        shareRepository
            .findByProfileIdAndStatus(visitor.getId(), ProfileShareStatus.ACTIVE)
            .stream()
            .findFirst()
            .orElseThrow();
    perform(
        authTestSupport.accountBearer(cabin),
        "endProfileShare",
        "{shareId: \"%s\"}".formatted(share.getId()));
    return visitor;
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ServerAdminReader.class)
  @DisplayName(
      "Should keep refusing a ServerAdmin as before when only the Profile's managers may do this")
  void shouldKeepRefusingServerAdminAsBeforeWhenOnlyProfilesManagersMayDoThis(
      ServerAdminReader reader) throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    if (reader == ServerAdminReader.ALSO_SUPERVISES_IT) {
      cast.makeAvailable(kid, serverAdmin);
    }

    var bearer = authTestSupport.accountBearer(serverAdmin);

    assertForbidden(bearer, ProfileMutation.SET_PROFILE_LANGUAGE_PREFERENCES, kid);
    assertThat(
            answer(bearer, ProfileMutation.DELETE_PROFILE, kid.getId())
                .at("/data/deleteProfile/userErrors/0/__typename")
                .asString())
        .isEqualTo("ProfileNotDeletableError");
  }

  /** What a ServerAdmin that does not manage the Profile is to it besides. */
  enum ServerAdminReader {
    NEITHER_MANAGES_NOR_HOSTS_IT,
    ALSO_SUPERVISES_IT
  }

  @Test
  @DisplayName(
      "Should keep forbidding a manager when it would restrict someone's own unrestricted Personal"
          + " Profile")
  void shouldKeepForbiddingManagerWhenItWouldRestrictSovereignPersonalProfile() throws Exception {
    var robin = authTestSupport.createHouseholdMemberIdentity(smith);
    cast.grant(cabin, robin.profile());

    assertForbidden(
        authTestSupport.accountBearer(cabin),
        ProfileMutation.SET_PROFILE_MAXIMUM_ALLOWED_RATING_AGE,
        robin.profile());
  }

  private void assertForbidden(String bearer, ProfileMutation mutation, Profile profile)
      throws Exception {
    var before = storedStateOf(profile);

    var answer = answer(bearer, mutation, profile.getId());

    assertThat(answer.at("/errors/0/extensions/code").asString()).isEqualTo("FORBIDDEN");
    assertThat(answer.at("/data/" + mutation.field).isNull()).isTrue();
    assertThat(storedStateOf(profile)).isEqualTo(before);
  }

  private void assertUserErrors(JsonNode answer, ProfileMutation mutation, String expected) {
    assertThat(answer.path("errors").isMissingNode()).as("top-level errors").isTrue();
    assertThat(answer.at("/data/%s/userErrors".formatted(mutation.field)))
        .isEqualTo(objectMapper.readTree(expected));
  }

  private void removeGrantAsServerAdmin(TestIdentity manager, Profile profile) throws Exception {
    perform(
        authTestSupport.freshAccountBearer(track(authTestSupport.createAdminIdentity())),
        "administrativelyRemoveProfileManager",
        "{profileId: \"%s\", accountId: \"%s\", reason: \"Asked to step back\"}"
            .formatted(profile.getId(), manager.account().getId()));
  }

  private JsonNode profileAdministration(TestIdentity reader, Profile profile) throws Exception {
    var body =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(reader),
                    PROFILE_ADMINISTRATION.formatted(profile.getId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body).at("/data/profileAdministration");
  }

  private JsonNode answer(String bearer, ProfileMutation mutation, UUID profileId)
      throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(bearer, mutation.request(profileId)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(body);
  }

  private void perform(String bearer, String mutation, String input) throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                bearer,
                "mutation { %s(input: %s) { userErrors { __typename } } }"
                    .formatted(mutation, input)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.%s.userErrors".formatted(mutation)).isEmpty());
  }

  /** What the Profile holds, so that a refusal is seen to change none of it. */
  private StoredState storedStateOf(Profile profile) {
    var stored = profileRepository.findById(profile.getId()).orElseThrow();
    return new StoredState(
        stored.getName(),
        stored.getKind(),
        stored.getMaximumAllowedRatingAge(),
        stored.getPicture(),
        stored.getPinHash(),
        languagePreferenceRepository.findProfilePreferences(profile.getId()));
  }

  private record StoredState(
      String name,
      ProfileKind kind,
      Integer maximumAllowedRatingAge,
      String picture,
      String pinHash,
      Optional<ProfileLanguagePreferences> languagePreferences) {}

  private TestIdentity asItIsNow(TestIdentity identity) {
    var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
    return new TestIdentity(
        account,
        identity.household(),
        identity.profile(),
        identity.session(),
        identity.rawRefreshToken());
  }

  private record Asking(String bearer, UUID profileId) {}

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
