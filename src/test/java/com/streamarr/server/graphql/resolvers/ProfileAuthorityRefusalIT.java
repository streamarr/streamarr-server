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
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.language.LanguagePreferenceRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
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
import tools.jackson.databind.node.ObjectNode;

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

  private static final String NOT_HOUSEHOLD_ADMIN =
      """
      [{"__typename": "ProfileManagersOnlyError",
        "message": "You are no longer an admin of the Household, so only the Profile's managers may do this.",
        "reason": "NOT_HOUSEHOLD_ADMIN"}]
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
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private LanguagePreferenceRepository languagePreferenceRepository;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity smith;
  private TestIdentity cabin;
  private Profile kid;
  private String lakeShareCode;

  @BeforeEach
  void setUp() {
    smith = track(authTestSupport.createIdentity());
    cabin = track(authTestSupport.createIdentity());
    kid = cast.profileManagedBy(smith, ProfileFixture.kidProfileBuilder());
    lakeShareCode = authTestSupport.shareCodeFor(track(authTestSupport.createIdentity()));
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  /**
   * Every mutation that only some of a Profile's readers may make, and what else it takes given a
   * live share code.
   */
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
    DELETE_PROFILE("deleteProfile", ""),
    INVITE_PROFILE_MANAGER("inviteProfileManager", ", email: \"sam@example.test\""),
    OFFER_PROFILE_SHARE(
        "offerProfileShare", shareCode -> ", shareCode: \"%s\"".formatted(shareCode));

    private final String field;
    private final UnaryOperator<String> otherInput;

    ProfileMutation(String field, String otherInput) {
      this(field, _ -> otherInput);
    }

    ProfileMutation(String field, UnaryOperator<String> otherInput) {
      this.field = field;
      this.otherInput = otherInput;
    }

    String request(UUID profileId, String shareCode) {
      return """
          mutation { %s(input: {profileId: "%s"%s}) { userErrors { __typename
            ... on MutationError { message } ... on InputMutationError { inputPath }
            ... on ProfileManagersOnlyError { reason } } } }
          """
          .formatted(field, profileId, otherInput.apply(shareCode));
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ProfileMutation.class,
      names = {
        "CHANGE_PROFILE_KIND",
        "SET_PROFILE_LANGUAGE_PREFERENCES",
        "DELETE_PROFILE",
        "INVITE_PROFILE_MANAGER",
        "OFFER_PROFILE_SHARE"
      })
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
    DISABLED_MANAGER;

    /** Neither a device-bound session nor a disabled Account presents a share code at all. */
    boolean presentsShareCodes() {
      return this != DEVICE_BOUND_SESSION_OF_ITS_MANAGER && this != DISABLED_MANAGER;
    }
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
      assertThat(refusalIn(forMissing, mutation))
          .as("%s answers a missing Profile", mutation)
          .isEqualTo(missingProfileRefusal(outsider, mutation));

      assertThat(answer(asking.bearer(), mutation, asking.profileId()))
          .as("%s answers %s", mutation, outsider)
          .isEqualTo(forMissing);
    }
  }

  // A caller that presents no share code is refused the offer before any Profile is looked up.
  private static String missingProfileRefusal(Outsider outsider, ProfileMutation mutation) {
    if (mutation == ProfileMutation.OFFER_PROFILE_SHARE && !outsider.presentsShareCodes()) {
      return "FORBIDDEN";
    }

    return "ProfileNotFoundError";
  }

  private static String refusalIn(JsonNode answer, ProfileMutation mutation) {
    var userError = answer.at("/data/%s/userErrors/0/__typename".formatted(mutation.field));
    if (userError.isMissingNode()) {
      return answer.at("/errors/0/extensions/code").asString();
    }

    return userError.asString();
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
        revokeHouseholdAdmin(supervisor);
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

  /**
   * A page that stays open while its Account stops being a HouseholdAdmin: its session keeps the
   * token issued before, which still reads the Profile there until it is renewed (ADR 0024
   * §Stateless JWT snapshots).
   */
  enum StaleAdminSession {
    DEMOTED_SUPERVISOR_OF_A_RESTRICTED_PROFILE,
    DEMOTED_HOST_OF_AN_UNRESTRICTED_VISITOR,
    SUPERVISOR_MOVED_TO_ADMINISTER_ANOTHER_HOUSEHOLD
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(StaleAdminSession.class)
  @DisplayName(
      "Should say the caller is no longer an admin of the Household when a session issued before it"
          + " stopped being one still reads the Profile")
  void shouldSayCallerIsNoLongerAdminWhenSessionIssuedBeforeItStoppedBeingOneStillReadsProfile(
      StaleAdminSession session) throws Exception {
    var stale = staleReadingOf(session);
    var before = storedStateOf(stale.profile());

    assertThat(profileAdministration(stale.bearer(), stale.profile()).path("id").asString())
        .isEqualTo(stale.profile().getId().toString());
    for (var mutation : ProfileMutation.values()) {
      var answer = answer(stale.bearer(), mutation, stale.profile().getId());

      assertThat(answer.path("errors").isMissingNode())
          .as("%s top-level errors", mutation)
          .isTrue();
      assertThat(answer.at("/data/%s/userErrors".formatted(mutation.field)))
          .as("%s refusal", mutation)
          .isEqualTo(objectMapper.readTree(NOT_HOUSEHOLD_ADMIN));
    }

    assertThat(storedStateOf(stale.profile())).isEqualTo(before);
  }

  private StaleReading staleReadingOf(StaleAdminSession session) throws Exception {
    return switch (session) {
      case DEMOTED_SUPERVISOR_OF_A_RESTRICTED_PROFILE -> {
        var supervisor = cast.secondAdminOf(smith);
        var bearer = authTestSupport.accountBearer(supervisor);
        revokeHouseholdAdmin(supervisor);
        yield new StaleReading(bearer, kid);
      }
      case DEMOTED_HOST_OF_AN_UNRESTRICTED_VISITOR -> {
        var visitor =
            cast.unavailableProfileManagedBy(smith, ProfileFixture.defaultProfileBuilder());
        cast.makeAvailable(visitor, cabin);
        var host = cast.secondAdminOf(cabin);
        var bearer = authTestSupport.accountBearer(host);
        revokeHouseholdAdmin(host);
        yield new StaleReading(bearer, visitor);
      }
      case SUPERVISOR_MOVED_TO_ADMINISTER_ANOTHER_HOUSEHOLD -> {
        var supervisor = cast.secondAdminOf(smith);
        var bearer = authTestSupport.accountBearer(supervisor);
        var serverAdmin =
            authTestSupport.accountBearer(track(authTestSupport.createAdminIdentity()));
        perform(
            serverAdmin,
            "transferAccount",
            "{accountId: \"%s\", destinationHouseholdId: \"%s\"}"
                .formatted(supervisor.account().getId(), cabin.household().getId()));
        perform(
            serverAdmin,
            "grantHouseholdAdmin",
            "{accountId: \"%s\"}".formatted(supervisor.account().getId()));
        yield new StaleReading(bearer, kid);
      }
    };
  }

  private void revokeHouseholdAdmin(TestIdentity admin) throws Exception {
    perform(
        authTestSupport.accountBearer(track(authTestSupport.createAdminIdentity())),
        "revokeHouseholdAdmin",
        "{accountId: \"%s\"}".formatted(admin.account().getId()));
  }

  private record StaleReading(String bearer, Profile profile) {}

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
    assertForbidden(bearer, ProfileMutation.INVITE_PROFILE_MANAGER, kid);
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

  @Test
  @DisplayName(
      "Should keep forbidding a manager when it offers someone's own unrestricted Personal Profile")
  void shouldKeepForbiddingManagerWhenItOffersSovereignPersonalProfile() throws Exception {
    var robin = authTestSupport.createHouseholdMemberIdentity(smith);
    cast.grant(cabin, robin.profile());

    assertForbidden(
        authTestSupport.accountBearer(cabin), ProfileMutation.OFFER_PROFILE_SHARE, robin.profile());
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
    return profileAdministration(authTestSupport.accountBearer(reader), profile);
  }

  private JsonNode profileAdministration(String bearer, Profile profile) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(bearer, PROFILE_ADMINISTRATION.formatted(profile.getId())))
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
            .perform(graphqlRequest(bearer, mutation.request(profileId, lakeShareCode)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var answer = objectMapper.readTree(body);
    // Each request has its own id, so it is left out when two answers are compared.
    answer
        .path("errors")
        .forEach(
            error -> {
              if (error.get("extensions") instanceof ObjectNode extensions) {
                extensions.remove("requestId");
              }
            });
    return answer;
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
        languagePreferenceRepository.findProfilePreferences(profile.getId()),
        shareRepository.findByProfileId(profile.getId()).size(),
        invitationRepository.existsWaitingForProfile(profile.getId(), Instant.now()));
  }

  private record StoredState(
      String name,
      ProfileKind kind,
      Integer maximumAllowedRatingAge,
      String picture,
      String pinHash,
      Optional<ProfileLanguagePreferences> languagePreferences,
      int shares,
      boolean managerInvitationWaiting) {}

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
