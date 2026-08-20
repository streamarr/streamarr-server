package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import org.jooq.DSLContext;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Profile administration through the GraphQL boundary against real PostgreSQL and the real Cedar
 * engine: creation with its anchor rules, the transition classifications and their
 * fresh-reauthentication boundary, PIN safety on clearing, the break-glass override audit, and
 * ordinary standalone deletion.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Administration Endpoints Integration Tests")
class ProfileAdministrationEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity serverAdmin;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createIdentity();
    serverAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(admin);
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should create a Kid Profile anchored by its creating HouseholdAdmin")
  void shouldCreateKidProfileAnchoredByCreatingHouseholdAdmin() throws Exception {
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID}) {
              profile { name kind restricted pinConfigured linked } userErrors { __typename } } }
            """
                .formatted(admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.createProfile.profile.kind").value("KID"))
        .andExpect(jsonPath("$.data.createProfile.profile.restricted").value(true))
        .andExpect(jsonPath("$.data.createProfile.profile.linked").value(false))
        .andExpect(jsonPath("$.data.createProfile.userErrors").isEmpty());
  }

  @Test
  @DisplayName("Should refuse a remote ServerAdmin Kid creation without a local anchor")
  void shouldRefuseRemoteServerAdminKidCreationWithoutLocalAnchor() throws Exception {
    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.createProfile.userErrors[0].__typename")
                .value("HomeAnchorRequiredError"));

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID,
              localManagerAccountId: "%s"}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.household().getId(), admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createProfile.userErrors").isEmpty());
  }

  @Test
  @DisplayName("Should refuse a duplicate available name through the deferred invariant")
  void shouldRefuseDuplicateAvailableNameThroughDeferredInvariant() throws Exception {
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "%s", kind: ADULT}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.household().getId(), admin.profile().getName().toUpperCase()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.createProfile.userErrors[0].__typename")
                .value("ProfileNameTakenError"));
  }

  @Test
  @DisplayName("Should require fresh reauthentication to lift the final restriction")
  void shouldRequireFreshReauthenticationToLiftFinalRestriction() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { changeProfileKind(input: {profileId: "%s", kind: ADULT}) {
              profile { kind } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeProfileKind.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { changeProfileKind(input: {profileId: "%s", kind: ADULT}) {
              profile { kind restricted } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.changeProfileKind.profile.kind").value("ADULT"))
        .andExpect(jsonPath("$.data.changeProfileKind.profile.restricted").value(false));
  }

  @Test
  @DisplayName("Should apply an ordinary ceiling edit without a ceremony")
  void shouldApplyOrdinaryCeilingEditWithoutCeremony() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfileContentCeiling(input: {profileId: "%s", maximumAllowedRatingAge: 12}) {
              profile { maximumAllowedRatingAge } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.setProfileContentCeiling.profile.maximumAllowedRatingAge").value(12));
  }

  @Test
  @DisplayName("Should rename, repicture, and clear the ceiling through ordinary edits")
  void shouldRenameRepictureAndClearCeilingThroughOrdinaryEdits() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { renameProfile(input: {profileId: "%s", name: "Kai Renamed"}) {
              profile { name } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.renameProfile.profile.name").value("Kai Renamed"));

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { renameProfile(input: {profileId: "%s", name: "%s"}) {
              profile { name } userErrors { __typename } } }
            """
                .formatted(kid.getId(), admin.profile().getName()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.renameProfile.userErrors[0].__typename")
                .value("ProfileNameTakenError"));

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfilePicture(input: {profileId: "%s", picture: "kai.png"}) {
              profile { picture } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.setProfilePicture.profile.picture").value("kai.png"));

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfileContentCeiling(input: {profileId: "%s", maximumAllowedRatingAge: 12}) {
              profile { maximumAllowedRatingAge } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk());
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { clearProfileContentCeiling(input: {profileId: "%s"}) {
              profile { maximumAllowedRatingAge kind } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.clearProfileContentCeiling.profile.maximumAllowedRatingAge")
                .doesNotExist())
        .andExpect(jsonPath("$.data.clearProfileContentCeiling.profile.kind").value("KID"));
  }

  @Test
  @DisplayName("Should refuse clearing a PIN that a Household's safety policy requires")
  void shouldRefuseClearingPinHouseholdSafetyPolicyRequires() throws Exception {
    kidProfile();
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfilePin(input: {profileId: "%s", pin: "4242"}) {
              profile { pinConfigured } userErrors { __typename } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.setProfilePin.profile.pinConfigured").value(true));

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { clearProfilePin(input: {profileId: "%s"}) {
              profile { id } userErrors { __typename ... on WouldLockProfileError { householdId message } } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.clearProfilePin.userErrors[0].__typename")
                .value("WouldLockProfileError"))
        .andExpect(
            jsonPath("$.data.clearProfilePin.userErrors[0].householdId")
                .value(admin.household().getId().toString()));
  }

  @Test
  @DisplayName("Should refuse a malformed PIN with its input path")
  void shouldRefuseMalformedPinWithItsInputPath() throws Exception {
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfilePin(input: {profileId: "%s", pin: "abc"}) {
              profile { id } userErrors { __typename ... on InputMutationError { inputPath } } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.setProfilePin.userErrors[0].__typename").value("PinMalformedError"))
        .andExpect(jsonPath("$.data.setProfilePin.userErrors[0].inputPath[0]").value("pin"));
  }

  @Test
  @DisplayName("Should audit the ServerAdmin PIN override once")
  void shouldAuditServerAdminPinOverrideOnce() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { overrideProfilePin(input: {profileId: "%s", pin: "4242", reason: "locked out"}) {
              profile { pinConfigured } userErrors { __typename } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.overrideProfilePin.profile.pinConfigured").value(true));

    var audits =
        dsl.selectFrom(SECURITY_AUDIT_EVENT)
            .where(SECURITY_AUDIT_EVENT.OPERATION.eq("overrideProfilePin"))
            .fetch();
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst().getReason()).isEqualTo("locked out");
  }

  @Test
  @DisplayName("Should delete an unlinked unshared Profile only for its fresh sole manager")
  void shouldDeleteUnlinkedUnsharedProfileOnlyForFreshSoleManager() throws Exception {
    var orphan = unsharedManagedProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteProfile.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.deleteProfile.deletedProfileId").value(orphan.getId().toString()));

    assertThat(profileRepository.findById(orphan.getId())).isEmpty();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should explain an undeletable Profile only to a caller who may view it")
  void shouldExplainUndeletableProfileOnlyToCallerWhoMayViewIt() throws Exception {
    var shared = kidProfile();

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(shared.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteProfile.userErrors[0].__typename")
                .value("ProfileNotDeletableError"));
  }

  @Test
  @DisplayName("Should show Profile administration to a manager and hide it from a stranger")
  void shouldShowProfileAdministrationToManagerAndHideItFromStranger() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { profileAdministration(profileId: "%s") { name kind householdId linked } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileAdministration.kind").value("KID"));

    var stranger = authTestSupport.createIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(stranger),
              """
              query { profileAdministration(profileId: "%s") { name } }
              """
                  .formatted(kid.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.profileAdministration").doesNotExist());
    } finally {
      authTestSupport.deleteIdentity(stranger);
    }
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  /** A Kid Profile in the admin's Household, anchored by the admin, available there. */
  private Profile kidProfile() {
    return transactionTemplate.execute(
        _ -> {
          var kid =
              profileRepository.saveAndFlush(
                  ProfileFixture.kidProfileBuilder()
                      .householdId(admin.household().getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(kid.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(kid.getId())
                  .householdId(admin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return kid;
        });
  }

  /** An unlinked Adult Profile the admin solely manages, not available anywhere. */
  private Profile unsharedManagedProfile() {
    return transactionTemplate.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(admin.household().getId())
                      .kind(ProfileKind.ADULT)
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(orphan.getId())
                  .build());
          return orphan;
        });
  }
}
