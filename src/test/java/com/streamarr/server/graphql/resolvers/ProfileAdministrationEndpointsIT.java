package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.enums.ProfileKind.ADULT;
import static com.streamarr.server.jooq.generated.tables.Profile.PROFILE;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private JdbcTemplate jdbcTemplate;

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
  @DisplayName("Should create a Kid Profile with its relationships when a HouseholdAdmin creates")
  void shouldCreateKidProfileWithItsRelationshipsWhenHouseholdAdminCreates() throws Exception {
    var result =
        graphql(
                authTestSupport.accountBearer(admin),
                """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID}) {
              profile { id name kind restricted pinConfigured linked } userErrors { __typename } } }
            """
                    .formatted(admin.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.createProfile.profile.kind").value("KID"))
            .andExpect(jsonPath("$.data.createProfile.profile.restricted").value(true))
            .andExpect(jsonPath("$.data.createProfile.profile.linked").value(false))
            .andExpect(jsonPath("$.data.createProfile.userErrors").isEmpty())
            .andReturn();

    var response = objectMapper.readTree(result.getResponse().getContentAsString());
    var profileId = UUID.fromString(response.at("/data/createProfile/profile/id").asText());
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                admin.account().getId(), profileId))
        .isTrue();
    assertThat(shareRepository.isActivelyShared(profileId, admin.household().getId())).isTrue();
  }

  @Test
  @DisplayName("Should reject a negative Content Ceiling as a typed input error when creating")
  void shouldRejectNegativeContentCeilingAsTypedInputErrorWhenCreating() throws Exception {
    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID,
              maximumAllowedRatingAge: -1}) {
              profile { id } userErrors { __typename }
            } }
            """
                .formatted(admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.createProfile.profile").doesNotExist())
        .andExpect(
            jsonPath("$.data.createProfile.userErrors[0].__typename")
                .value("MaximumAllowedRatingAgeInvalidError"));
  }

  @Test
  @DisplayName("Should refuse a remote ServerAdmin Kid creation when a local anchor is omitted")
  void shouldRefuseRemoteServerAdminKidCreationWhenLocalAnchorIsOmitted() throws Exception {
    var profilesBefore = profileRepository.count();
    var managersBefore = profileManagerRepository.count();
    var sharesBefore = shareRepository.count();

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

    assertThat(profileRepository.count()).isEqualTo(profilesBefore);
    assertThat(profileManagerRepository.count()).isEqualTo(managersBefore);
    assertThat(shareRepository.count()).isEqualTo(sharesBefore);

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
  @DisplayName("Should reject and roll back creation when the named local manager is ineligible")
  void shouldRejectAndRollBackCreationWhenNamedLocalManagerIsIneligible() throws Exception {
    var ineligibleManager = joinHouseholdAsRestrictedMember();
    var profilesBefore = profileRepository.count();
    var managersBefore = profileManagerRepository.count();
    var sharesBefore = shareRepository.count();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: KID,
              localManagerAccountId: "%s"}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.household().getId(), ineligibleManager.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.createProfile.profile").doesNotExist())
        .andExpect(
            jsonPath("$.data.createProfile.userErrors[0].__typename")
                .value("ManagerNotEligibleError"));

    assertThat(profileRepository.count()).isEqualTo(profilesBefore);
    assertThat(profileManagerRepository.count()).isEqualTo(managersBefore);
    assertThat(shareRepository.count()).isEqualTo(sharesBefore);
  }

  @Test
  @DisplayName("Should not grant the named manager when a HouseholdAdmin creates")
  void shouldNotGrantNamedManagerWhenHouseholdAdminCreates() throws Exception {
    var outsider = authTestSupport.createIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(admin),
              """
              mutation { createProfile(input: {householdId: "%s", name: "Kai", kind: ADULT,
                localManagerAccountId: "%s"}) {
                profile { id } userErrors { __typename } } }
              """
                  .formatted(admin.household().getId(), outsider.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"))
          .andExpect(jsonPath("$.data.createProfile").doesNotExist());
    } finally {
      authTestSupport.deleteIdentity(outsider);
    }
  }

  @Test
  @DisplayName("Should refuse and roll back creation when an available name is duplicated")
  void shouldRefuseAndRollBackCreationWhenAvailableNameIsDuplicated() throws Exception {
    var profilesBefore = profileRepository.count();
    var managersBefore = profileManagerRepository.count();
    var sharesBefore = shareRepository.count();

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

    assertThat(profileRepository.count()).isEqualTo(profilesBefore);
    assertThat(profileManagerRepository.count()).isEqualTo(managersBefore);
    assertThat(shareRepository.count()).isEqualTo(sharesBefore);
  }

  @Test
  @DisplayName("Should require fresh reauthentication when lifting the final restriction")
  void shouldRequireFreshReauthenticationWhenLiftingFinalRestriction() throws Exception {
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
  @DisplayName("Should classify the committed policy when a concurrent transition holds the lock")
  void shouldClassifyCommittedPolicyWhenConcurrentTransitionHoldsLock() throws Exception {
    var kid = kidProfile();
    var transitionPrepared = new CountDownLatch(1);
    var commitTransition = new CountDownLatch(1);
    var transitionBackendPid = new AtomicInteger();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var transitioning =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        dsl.selectOne()
                            .from(PROFILE)
                            .where(PROFILE.ID.eq(kid.getId()))
                            .forUpdate()
                            .fetchSingle();
                        transitionBackendPid.set(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        dsl.update(PROFILE)
                            .set(PROFILE.KIND, ADULT)
                            .setNull(PROFILE.MAXIMUM_ALLOWED_RATING_AGE)
                            .where(PROFILE.ID.eq(kid.getId()))
                            .execute();
                        transitionPrepared.countDown();
                        await(commitTransition);
                      }));
      assertThat(transitionPrepared.await(30, TimeUnit.SECONDS)).isTrue();

      var changing =
          executor.submit(
              () ->
                  graphql(
                          authTestSupport.accountBearer(admin),
                          """
                          mutation { changeProfileKind(input: {profileId: "%s", kind: ADULT}) {
                            profile { kind restricted } userErrors { __typename } } }
                          """
                              .formatted(kid.getId()))
                      .andReturn());
      try {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> isWaitingForBackend(transitionBackendPid.get()));
      } finally {
        commitTransition.countDown();
      }
      transitioning.get(30, TimeUnit.SECONDS);
      var result = changing.get(30, TimeUnit.SECONDS);
      var response = objectMapper.readTree(result.getResponse().getContentAsString());

      assertThat(response.path("errors").isMissingNode()).isTrue();
      assertThat(response.path("data").path("changeProfileKind").path("userErrors")).isEmpty();
      assertThat(
              response.path("data").path("changeProfileKind").path("profile").path("kind").asText())
          .isEqualTo("ADULT");
      assertThat(
              response
                  .path("data")
                  .path("changeProfileKind")
                  .path("profile")
                  .path("restricted")
                  .asBoolean())
          .isFalse();
    }
  }

  @Test
  @DisplayName("Should fail closed when a ServerAdmin changes a missing Profile policy")
  void shouldFailClosedWhenServerAdminChangesMissingProfilePolicy() throws Exception {
    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { changeProfileKind(input: {profileId: "%s", kind: ADULT}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(UUID.randomUUID()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("AUTHORIZATION_UNAVAILABLE"))
        .andExpect(jsonPath("$.data.changeProfileKind.profile").doesNotExist())
        .andExpect(jsonPath("$.data.changeProfileKind.userErrors").doesNotExist());
  }

  @Test
  @DisplayName("Should apply an ordinary ceiling edit when a ceremony is absent")
  void shouldApplyOrdinaryCeilingEditWhenCeremonyIsAbsent() throws Exception {
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
  @DisplayName("Should reject a negative Content Ceiling as a typed input error when setting it")
  void shouldRejectNegativeContentCeilingAsTypedInputErrorWhenSettingIt() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfileContentCeiling(input: {profileId: "%s", maximumAllowedRatingAge: -1}) {
              profile { id }
              userErrors { __typename ... on InputMutationError { inputPath } }
            } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.setProfileContentCeiling.profile").doesNotExist())
        .andExpect(
            jsonPath("$.data.setProfileContentCeiling.userErrors[0].__typename")
                .value("MaximumAllowedRatingAgeInvalidError"))
        .andExpect(
            jsonPath("$.data.setProfileContentCeiling.userErrors[0].inputPath[0]")
                .value("maximumAllowedRatingAge"));
  }

  @Test
  @DisplayName(
      "Should return the authority rejection when restricting an Account that holds authority")
  void shouldReturnAuthorityRejectionWhenRestrictingAccountThatHoldsAuthority() throws Exception {
    establishReplacementAnchorForAdminProfile();

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { setProfileContentCeiling(input: {profileId: "%s", maximumAllowedRatingAge: 12}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.setProfileContentCeiling.profile").doesNotExist())
        .andExpect(
            jsonPath("$.data.setProfileContentCeiling.userErrors[0].__typename")
                .value("RestrictedAccountAuthorityError"));
  }

  @Test
  @DisplayName(
      "Should restrict the sovereign Adult when authority is removed and a replacement manager exists")
  void shouldRestrictSovereignAdultWhenAuthorityIsRemovedAndReplacementManagerExists()
      throws Exception {
    establishReplacementAnchorForAdminProfile();
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(admin.account().getId()).orElseThrow();
          account.setHouseholdRole(HouseholdRole.MEMBER);
          userAccountRepository.saveAndFlush(account);
        });

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { setProfileContentCeiling(input: {profileId: "%s", maximumAllowedRatingAge: 12}) {
              profile { maximumAllowedRatingAge } userErrors { __typename } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.setProfileContentCeiling.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.setProfileContentCeiling.profile.maximumAllowedRatingAge").value(12));
  }

  @Test
  @DisplayName("Should rename a Profile when its manager supplies an available name")
  void shouldRenameProfileWhenManagerSuppliesAvailableName() throws Exception {
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
  }

  @Test
  @DisplayName("Should reject a rename when an available Profile already uses the name")
  void shouldRejectRenameWhenAvailableProfileAlreadyUsesName() throws Exception {
    var kid = kidProfile();

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
  }

  @Test
  @DisplayName("Should set a Profile picture when its manager performs an ordinary edit")
  void shouldSetProfilePictureWhenManagerPerformsOrdinaryEdit() throws Exception {
    var kid = kidProfile();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { setProfilePicture(input: {profileId: "%s", picture: "kai.png"}) {
              profile { picture } userErrors { __typename } } }
            """
                .formatted(kid.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.setProfilePicture.profile.picture").value("kai.png"));
  }

  @Test
  @DisplayName("Should clear a Content Ceiling when its manager performs an ordinary edit")
  void shouldClearContentCeilingWhenManagerPerformsOrdinaryEdit() throws Exception {
    var kid = kidProfile();

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
  @DisplayName("Should refuse clearing a PIN when a Household's safety policy requires it")
  void shouldRefuseClearingPinWhenHouseholdSafetyPolicyRequiresIt() throws Exception {
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
  @DisplayName("Should return the cleared PIN state when the clear succeeds")
  void shouldReturnClearedPinStateWhenClearSucceeds() throws Exception {
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
              profile { pinConfigured } userErrors { __typename } } }
            """
                .formatted(admin.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.clearProfilePin.userErrors").isEmpty())
        .andExpect(jsonPath("$.data.clearProfilePin.profile.pinConfigured").value(false));
  }

  @Test
  @DisplayName("Should refuse the PIN with its input path when the PIN is malformed")
  void shouldRefusePinWithInputPathWhenPinIsMalformed() throws Exception {
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
  @DisplayName("Should audit the PIN override once when a fresh ServerAdmin overrides it")
  void shouldAuditPinOverrideOnceWhenFreshServerAdminOverridesIt() throws Exception {
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
  @DisplayName("Should delete an unlinked unshared Profile when its fresh sole manager requests it")
  void shouldDeleteUnlinkedUnsharedProfileWhenFreshSoleManagerRequestsIt() throws Exception {
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
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("deleteProfile")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject deletion when the Profile has a pending Household share")
  void shouldRejectDeletionWhenProfileHasPendingHouseholdShare() throws Exception {
    var orphan = unsharedManagedProfile();
    transactionTemplate.executeWithoutResult(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(orphan.getId())
                    .householdId(serverAdmin.household().getId())
                    .status(ProfileShareStatus.PENDING)
                    .build()));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteProfile.deletedProfileId").doesNotExist())
        .andExpect(
            jsonPath("$.data.deleteProfile.userErrors[0].__typename")
                .value("ProfileNotDeletableError"));

    assertThat(profileRepository.findById(orphan.getId())).isPresent();
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("deleteProfile")))
        .isZero();
  }

  @Test
  @DisplayName("Should reject deletion when a concurrent share commits first")
  void shouldRejectDeletionWhenConcurrentShareCommitsFirst() throws Exception {
    var orphan = unsharedManagedProfile();
    var sharePrepared = new CountDownLatch(1);
    var commitShare = new CountDownLatch(1);
    var sharingBackendPid = new AtomicInteger();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var sharing =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        dsl.selectOne()
                            .from(PROFILE)
                            .where(PROFILE.ID.eq(orphan.getId()))
                            .forUpdate()
                            .fetchSingle();
                        sharingBackendPid.set(
                            jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        shareRepository.saveAndFlush(
                            ProfileHouseholdShare.builder()
                                .profileId(orphan.getId())
                                .householdId(serverAdmin.household().getId())
                                .status(ProfileShareStatus.ACTIVE)
                                .build());
                        sharePrepared.countDown();
                        await(commitShare);
                      }));
      assertThat(sharePrepared.await(30, TimeUnit.SECONDS)).isTrue();

      var deleting =
          executor.submit(
              () ->
                  graphql(
                          authTestSupport.freshAccountBearer(admin),
                          """
                          mutation { deleteProfile(input: {profileId: "%s"}) {
                            deletedProfileId userErrors { __typename } } }
                          """
                              .formatted(orphan.getId()))
                      .andReturn());
      try {
        Awaitility.await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> isWaitingForBackend(sharingBackendPid.get()));
      } finally {
        commitShare.countDown();
      }
      sharing.get(30, TimeUnit.SECONDS);
      var result = deleting.get(30, TimeUnit.SECONDS);
      var response = objectMapper.readTree(result.getResponse().getContentAsString());

      assertThat(response.path("errors").isMissingNode()).isTrue();
      assertThat(response.path("data").path("deleteProfile").path("deletedProfileId").isNull())
          .isTrue();
      assertThat(
              response
                  .path("data")
                  .path("deleteProfile")
                  .path("userErrors")
                  .get(0)
                  .path("__typename")
                  .asText())
          .isEqualTo("ProfileNotDeletableError");
      assertThat(profileRepository.findById(orphan.getId())).isPresent();
      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("deleteProfile")))
          .isZero();
    }
  }

  @Test
  @DisplayName("Should explain an undeletable Profile when the caller may view it")
  void shouldExplainUndeletableProfileWhenCallerMayViewIt() throws Exception {
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
  @DisplayName("Should show or hide Profile administration when caller authority changes")
  void shouldShowOrHideProfileAdministrationWhenCallerAuthorityChanges() throws Exception {
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

  /** Another eligible HouseholdAdmin in the admin's Household, available as a home anchor. */
  private UserAccount joinHouseholdAsAdmin() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(admin.household().getId())
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(admin.household().getId())
                      .householdRole(HouseholdRole.ADMIN)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(admin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private UserAccount joinHouseholdAsRestrictedMember() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(admin.household().getId())
                      .maximumAllowedRatingAge(12)
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(admin.household().getId())
                      .householdRole(HouseholdRole.MEMBER)
                      .personalProfileId(profile.getId())
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(admin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private void establishReplacementAnchorForAdminProfile() {
    var replacementAnchor = joinHouseholdAsAdmin();
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(replacementAnchor.getId())
                    .profileId(admin.profile().getId())
                    .build()));
  }

  private boolean isWaitingForBackend(int blockingBackendPid) {
    var waiting =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM pg_stat_activity
              WHERE ? = ANY(pg_blocking_pids(pid))
            )
            """,
            Boolean.class,
            blockingBackendPid);
    return Boolean.TRUE.equals(waiting);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for the concurrent operation");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for the concurrent operation", e);
    }
  }
}
