package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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
 * The sharing lifecycle through the GraphQL boundary against real PostgreSQL and Cedar: offers and
 * their decisions, T7's eligible-admin activation rule, T8's name rule, T3's structural rule, the
 * fresh-reauthenticated force-end, the preflight, and the unshare session effects.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Sharing Endpoints Integration Tests")
class ProfileSharingEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    owner = authTestSupport.createIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(owner);
  }

  @Test
  @DisplayName("Should make a Profile available when its share offer is accepted")
  void shouldMakeProfileAvailableWhenShareOfferIsAccepted() throws Exception {
    var orphan = managedOrphan();

    var shareId = offer(orphan, host.household().getId());

    graphql(
            authTestSupport.accountBearer(host),
            """
            query { pendingShareOffers(householdId: "%s") { edges { node { id status } } } }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.pendingShareOffers.edges[0].node.id").value(shareId));

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"));

    assertThat(shareRepository.isActivelyShared(orphan.getId(), host.household().getId())).isTrue();

    // One live share per pair: a second offer refuses.
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
              share { id } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.offerProfileShare.userErrors[0].__typename")
                .value("ProfileAlreadySharedError"));
  }

  @Test
  @DisplayName("Should cancel the older offer when a pending offer is replaced")
  void shouldCancelOlderOfferWhenPendingOfferIsReplaced() throws Exception {
    var orphan = managedOrphan();
    var firstShareId = offer(orphan, host.household().getId());

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
              share { id status } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"));

    assertThat(shareRepository.findById(UUID.fromString(firstShareId)).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.CANCELED);
  }

  @Test
  @DisplayName("Should expire the older offer when an expired offer is replaced")
  void shouldExpireOlderOfferWhenExpiredOfferIsReplaced() throws Exception {
    var orphan = managedOrphan();
    var firstShareId = UUID.fromString(offer(orphan, host.household().getId()));
    transactionTemplate.executeWithoutResult(
        _ -> {
          var expired = shareRepository.findById(firstShareId).orElseThrow();
          expired.setExpiresAt(Instant.now().minusSeconds(1));
          shareRepository.saveAndFlush(expired);
        });

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
              share { id status } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"));

    assertThat(shareRepository.findById(firstShareId).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.EXPIRED);
  }

  @Test
  @DisplayName("Should invalidate a pending offer when its ServerAdmin loses authority")
  void shouldInvalidatePendingOfferWhenItsServerAdminLosesAuthority() throws Exception {
    var orphan = managedOrphan();
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      var response =
          graphql(
                  authTestSupport.accountBearer(serverAdmin),
                  """
                  mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                    share { id status } userErrors { __typename } } }
                  """
                      .formatted(orphan.getId(), host.household().getId()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.errors").doesNotExist())
              .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      var shareId =
          UUID.fromString(
              objectMapper
                  .readTree(response)
                  .path("data")
                  .path("offerProfileShare")
                  .path("share")
                  .path("id")
                  .asString());
      transactionTemplate.executeWithoutResult(
          _ -> {
            var revoked =
                userAccountRepository.findById(serverAdmin.account().getId()).orElseThrow();
            revoked.setServerAdmin(false);
            userAccountRepository.saveAndFlush(revoked);
          });

      graphql(
              authTestSupport.accountBearer(host),
              """
              mutation { acceptProfileShare(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.acceptProfileShare.userErrors[0].__typename")
                  .value("ShareNotPendingError"));
      assertThat(shareRepository.findById(shareId).orElseThrow().getStatus())
          .isEqualTo(ProfileShareStatus.INVALIDATED);
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should refuse activation when a restricted share would leave no eligible admin")
  void shouldRefuseActivationWhenRestrictedShareWouldLeaveNoEligibleAdmin() throws Exception {
    var kid = managedKid();
    var empty =
        householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
    try {
      var shareId = offer(kid, empty.getId());

      // ServerAdmin may accept on the Household's behalf, but T7 still needs an eligible admin.
      var serverAdmin = authTestSupport.createAdminIdentity();
      try {
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { acceptProfileShare(input: {shareId: "%s"}) {
                  share { status } userErrors { __typename } } }
                """
                    .formatted(shareId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.acceptProfileShare.userErrors[0].__typename")
                    .value("NoEligibleAdminError"));
      } finally {
        authTestSupport.deleteIdentity(serverAdmin);
      }
    } finally {
      householdRepository.deleteById(empty.getId());
    }
  }

  @Test
  @DisplayName("Should refuse activation when the Profile name conflicts in the target Household")
  void shouldRefuseActivationWhenProfileNameConflictsInTargetHousehold() throws Exception {
    var twin =
        transactionTemplate.execute(
            _ -> {
              var profile =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(owner.household().getId())
                          .name(host.profile().getName().toUpperCase())
                          .build());
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
    var shareId = offer(twin, host.household().getId());

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.acceptProfileShare.userErrors[0].__typename")
                .value("ShareNameConflictError"));
  }

  @Test
  @DisplayName("Should return not-pending when another decision has already won")
  void shouldReturnNotPendingWhenAnotherDecisionAlreadyWon() throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host.household().getId());

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { rejectProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { cancelProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.cancelProfileShare.userErrors[0].__typename")
                .value("ShareNotPendingError"));
  }

  @Test
  @DisplayName("Should clear visitor context and preserve structural shares when a visit ends")
  void shouldClearVisitorContextAndPreserveStructuralSharesWhenVisitEnds() throws Exception {
    // The owner's Personal Profile visits the host's Household.
    var visitShareId =
        transactionTemplate.execute(
            _ ->
                shareRepository
                    .saveAndFlush(
                        ProfileHouseholdShare.builder()
                            .profileId(owner.profile().getId())
                            .householdId(host.household().getId())
                            .status(ProfileShareStatus.ACTIVE)
                            .build())
                    .getId());
    var session = owner.session();
    session.setContextHouseholdId(host.household().getId());
    authSessionRepository.saveAndFlush(session);

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { endProfileShare(input: {shareId: "%s"}) {
              share { status endedAt } userErrors { __typename } } }
            """
                .formatted(visitShareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.endProfileShare.share.status").value("ENDED"));

    // The visitor's session dropped back to the membership Household.
    assertThat(
            authSessionRepository
                .findById(owner.session().getId())
                .orElseThrow()
                .getContextHouseholdId())
        .isNull();

    // Cedar refuses to end the structural share before the database constraint is reached.
    var structuralShareId =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                owner.profile().getId(), owner.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow()
            .getId();
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { endProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(structuralShareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.endProfileShare").doesNotExist())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should require a fresh ServerAdmin and audit when a share is force-ended")
  void shouldRequireFreshServerAdminAndAuditWhenShareIsForceEnded() throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host.household().getId());
    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk());

    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              mutation { forceEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.forceEndProfileShare.userErrors[0].__typename")
                  .value("ReauthenticationRequiredError"));

      graphql(
              authTestSupport.freshAccountBearer(serverAdmin),
              """
              mutation { forceEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.forceEndProfileShare.share.status").value("ENDED"));

      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("forceEndProfileShare")))
          .isEqualTo(1);
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should expose only lock and name facts when the offerer runs preflight")
  void shouldExposeOnlyLockAndNameFactsWhenOffererRunsPreflight() throws Exception {
    var kid = managedKid();
    var shareId = offer(kid, host.household().getId());
    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());

    // The owner's unpinned Adult Personal Profile would lock next to the Kid; its name is unique.
    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { sharePreflight(profileId: "%s", householdId: "%s") { wouldLock nameConflict } }
            """
                .formatted(owner.profile().getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sharePreflight.wouldLock").value(true))
        .andExpect(jsonPath("$.data.sharePreflight.nameConflict").value(false));
  }

  @Test
  @DisplayName("Should return the final share when last is supplied without before")
  void shouldReturnFinalShareWhenLastIsSuppliedWithoutBefore() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host.household().getId());

    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileShares(profileId: "%s", last: 1) {
              edges { node { id } } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileShares.edges[0].node.id").exists());
  }

  @Test
  @DisplayName("Should apply the default page size when before is supplied without last")
  void shouldApplyDefaultPageSizeWhenBeforeIsSuppliedWithoutLast() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host.household().getId());
    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                query { profileShares(profileId: "%s", first: 2) {
                  edges { cursor node { id } } } }
                """
                    .formatted(orphan.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var before =
        objectMapper
            .readTree(response)
            .path("data")
            .path("profileShares")
            .path("edges")
            .path(1)
            .path("cursor")
            .asString();

    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileShares(profileId: "%s", before: "%s") {
              edges { node { id } } } }
            """
                .formatted(orphan.getId(), before))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileShares.edges[0].node.id").exists());
  }

  private String offer(Profile profile, UUID householdId) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                  share { id status } userErrors { __typename } } }
                """
                    .formatted(profile.getId(), householdId))
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

  /** An unlinked Adult Profile the owner solely manages, available at home. */
  private Profile managedOrphan() {
    return managedProfile(ProfileFixture.defaultProfileBuilder());
  }

  /** A Kid Profile the owner manages, anchored by the owner (a HouseholdAdmin). */
  private Profile managedKid() {
    return managedProfile(ProfileFixture.kidProfileBuilder());
  }

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

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }
}
