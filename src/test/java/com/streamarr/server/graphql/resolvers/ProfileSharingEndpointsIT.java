package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
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
 * their decisions, activation eligibility, name conflicts, membership-required shares, force-ending
 * after password confirmation, previews, and visitor-session effects.
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
  @Autowired private DataSource dataSource;

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

    // A Profile can have only one pending or active share per Household.
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

      // ServerAdmin may accept on the Household's behalf, but the Household still needs an
      // eligible HouseholdAdmin.
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
                    .value("RestrictedProfileRequiresHouseholdAdminError"));
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
  @DisplayName("Should allow one decision when acceptance and rejection race")
  void shouldAllowOneDecisionWhenAcceptanceAndRejectionRace() throws Exception {
    var orphan = managedOrphan();
    var shareId = UUID.fromString(offer(orphan, host.household().getId()));
    var bearer = authTestSupport.accountBearer(host);

    var responses =
        raceWhileShareLocked(
            shareId,
            () ->
                graphql(
                        bearer,
                        """
                        mutation { acceptProfileShare(input: {shareId: "%s"}) {
                          share { status } userErrors { __typename } } }
                        """
                            .formatted(shareId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString(),
            () ->
                graphql(
                        bearer,
                        """
                        mutation { rejectProfileShare(input: {shareId: "%s"}) {
                          share { status } userErrors { __typename } } }
                        """
                            .formatted(shareId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());

    var results =
        List.of(
            mutationResult(responses.getFirst(), "acceptProfileShare"),
            mutationResult(responses.getLast(), "rejectProfileShare"));
    assertThat(results).extracting(MutationResult::accepted).containsExactlyInAnyOrder(true, false);
    assertThat(results)
        .filteredOn(result -> !result.accepted())
        .extracting(MutationResult::errorType)
        .containsExactly("ShareNotPendingError");
    assertThat(shareRepository.findById(shareId).orElseThrow().getStatus())
        .isIn(ProfileShareStatus.ACTIVE, ProfileShareStatus.REJECTED);
  }

  @Test
  @DisplayName(
      "Should clear visitor context and preserve membership-required shares when a visit ends")
  void shouldClearVisitorContextAndPreserveMembershipRequiredSharesWhenVisitEnds()
      throws Exception {
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

    // Cedar refuses to end the membership-required share before the database constraint runs.
    var membershipShareId =
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
                .formatted(membershipShareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.endProfileShare").doesNotExist())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should forbid an unrelated ServerAdmin from ordinarily ending a share")
  void shouldForbidUnrelatedServerAdminFromOrdinarilyEndingShare() throws Exception {
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
              mutation { endProfileShare(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.endProfileShare").doesNotExist())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
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
  @DisplayName("Should audit one winner when two force-end requests race")
  void shouldAuditOneWinnerWhenTwoForceEndRequestsRace() throws Exception {
    var orphan = managedOrphan();
    var shareId = UUID.fromString(offer(orphan, host.household().getId()));
    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"));

    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      var bearer = authTestSupport.freshAccountBearer(serverAdmin);
      var forceEnd =
          """
          mutation { forceEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
            share { status } userErrors { __typename } } }
          """
              .formatted(shareId);
      var responses =
          raceWhileShareLocked(
              shareId,
              () ->
                  graphql(bearer, forceEnd)
                      .andExpect(status().isOk())
                      .andReturn()
                      .getResponse()
                      .getContentAsString(),
              () ->
                  graphql(bearer, forceEnd)
                      .andExpect(status().isOk())
                      .andReturn()
                      .getResponse()
                      .getContentAsString());

      var results =
          responses.stream()
              .map(response -> mutationResult(response, "forceEndProfileShare"))
              .toList();
      assertThat(results)
          .extracting(MutationResult::accepted)
          .containsExactlyInAnyOrder(true, false);
      assertThat(results)
          .filteredOn(result -> !result.accepted())
          .extracting(MutationResult::errorType)
          .containsExactly("ShareNotActiveError");
      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("forceEndProfileShare")))
          .isEqualTo(1);
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should expose only lock and name facts when the offerer requests a preview")
  void shouldExposeOnlyLockAndNameFactsWhenOffererRequestsPreview() throws Exception {
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
            query { profileSharePreview(profileId: "%s", householdId: "%s") { wouldLock nameConflict } }
            """
                .formatted(owner.profile().getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.profileSharePreview.wouldLock").value(true))
        .andExpect(jsonPath("$.data.profileSharePreview.nameConflict").value(false));
  }

  @Test
  @DisplayName("Should return the final share when last is supplied without before")
  void shouldReturnFinalShareWhenLastIsSuppliedWithoutBefore() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host.household().getId());
    var expectedId = orderedShareIds(orphan.getId()).getLast();

    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileShares(profileId: "%s", last: 1) {
              edges { node { id requiredByAccountMembership } }
              pageInfo { hasNextPage } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileShares.edges.length()").value(1))
        .andExpect(jsonPath("$.data.profileShares.edges[0].node.id").value(expectedId.toString()))
        .andExpect(
            jsonPath("$.data.profileShares.edges[0].node.requiredByAccountMembership").value(false))
        .andExpect(jsonPath("$.data.profileShares.pageInfo.hasNextPage").value(false));
  }

  @Test
  @DisplayName("Should apply the default page size when before is supplied without last")
  void shouldApplyDefaultPageSizeWhenBeforeIsSuppliedWithoutLast() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host.household().getId());
    var endedShares = new ArrayList<ProfileHouseholdShare>();
    for (var index = 0; index < 105; index++) {
      endedShares.add(
          ProfileHouseholdShare.builder()
              .profileId(orphan.getId())
              .householdId(host.household().getId())
              .status(ProfileShareStatus.ENDED)
              .endedAt(Instant.now())
              .build());
    }

    shareRepository.saveAllAndFlush(endedShares);
    var orderedIds = orderedShareIds(orphan.getId());
    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                query { profileShares(profileId: "%s", last: 1) {
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
            .path(0)
            .path("cursor")
            .asString();

    var beforeResponse =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                query { profileShares(profileId: "%s", before: "%s") {
                  edges { node { id } } } }
                """
                    .formatted(orphan.getId(), before))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    var expectedIds =
        orderedIds.subList(orderedIds.size() - 101, orderedIds.size() - 1).stream()
            .map(UUID::toString)
            .toList();
    assertThat(edgeIds(beforeResponse)).containsExactlyElementsOf(expectedIds);
  }

  @Test
  @DisplayName("Should return the next share when an after cursor is supplied")
  void shouldReturnNextShareWhenAfterCursorIsSupplied() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host.household().getId());
    var orderedIds = orderedShareIds(orphan.getId());
    var firstPage =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                query { profileShares(profileId: "%s", first: 1) {
                  edges { cursor node { id } } } }
                """
                    .formatted(orphan.getId()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var after =
        objectMapper
            .readTree(firstPage)
            .path("data")
            .path("profileShares")
            .path("edges")
            .path(0)
            .path("cursor")
            .asString();

    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileShares(profileId: "%s", after: "%s", first: 1) {
              edges { node { id } } } }
            """
                .formatted(orphan.getId(), after))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.profileShares.edges[0].node.id").value(orderedIds.get(1).toString()));
  }

  private List<String> raceWhileShareLocked(
      UUID shareId, Callable<String> firstMutation, Callable<String> secondMutation)
      throws Exception {
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var blocker =
            executor.submit(
                () -> {
                  holdShareRowLock(shareId, rowLocked, releaseRow);
                  return null;
                });
        assertThat(rowLocked.await(10, TimeUnit.SECONDS))
            .as("share row should be locked before racing mutations")
            .isTrue();

        var first = executor.submit(firstMutation);
        var second = executor.submit(secondMutation);
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(
                () ->
                    assertThat(waitingProfileShareTransitions())
                        .as("both mutations should wait on the same share transition")
                        .isEqualTo(2));

        releaseRow.countDown();
        var responses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        blocker.get(10, TimeUnit.SECONDS);
        return responses;
      } finally {
        releaseRow.countDown();
      }
    }
  }

  private void holdShareRowLock(UUID shareId, CountDownLatch rowLocked, CountDownLatch releaseRow)
      throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              "SELECT id FROM profile_household_share WHERE id = ? FOR UPDATE")) {
        statement.setObject(1, shareId);
        statement.executeQuery();
      }

      rowLocked.countDown();
      assertThat(releaseRow.await(10, TimeUnit.SECONDS))
          .as("share row should be released by the race")
          .isTrue();
      connection.rollback();
    }
  }

  private int waitingProfileShareTransitions() {
    return dsl.fetchOne(
            """
            SELECT count(*)
            FROM pg_stat_activity
            WHERE wait_event_type = 'Lock'
              AND query ILIKE '%update%profile_household_share%'
            """)
        .get(0, int.class);
  }

  private List<UUID> orderedShareIds(UUID profileId) {
    return dsl.select(PROFILE_HOUSEHOLD_SHARE.ID)
        .from(PROFILE_HOUSEHOLD_SHARE)
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
        .orderBy(PROFILE_HOUSEHOLD_SHARE.ID.asc())
        .fetch(PROFILE_HOUSEHOLD_SHARE.ID);
  }

  private List<String> edgeIds(String response) {
    var edges = objectMapper.readTree(response).path("data").path("profileShares").path("edges");
    var ids = new ArrayList<String>();
    edges.forEach(edge -> ids.add(edge.path("node").path("id").asString()));
    return ids;
  }

  private MutationResult mutationResult(String response, String operation) {
    var payload = objectMapper.readTree(response).path("data").path(operation);
    var accepted = !payload.path("share").isMissingNode() && !payload.path("share").isNull();
    var errorType = payload.path("userErrors").path(0).path("__typename").asString(null);
    return new MutationResult(accepted, errorType);
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

  /** A Kid Profile managed by its owner, who is a HouseholdAdmin. */
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

  private record MutationResult(boolean accepted, String errorType) {}

  // ---- A missing share answers ShareNotFound for every verb, whatever the policy arm.

  @Test
  @DisplayName("Should answer share-not-found when a ServerAdmin accepts a missing share")
  void shouldAnswerShareNotFoundWhenServerAdminAcceptsMissingShare() throws Exception {
    decideMissingShareAsServerAdmin("acceptProfileShare");
  }

  @Test
  @DisplayName("Should answer share-not-found when a ServerAdmin rejects a missing share")
  void shouldAnswerShareNotFoundWhenServerAdminRejectsMissingShare() throws Exception {
    decideMissingShareAsServerAdmin("rejectProfileShare");
  }

  @Test
  @DisplayName("Should answer share-not-found when a ServerAdmin cancels a missing share")
  void shouldAnswerShareNotFoundWhenServerAdminCancelsMissingShare() throws Exception {
    decideMissingShareAsServerAdmin("cancelProfileShare");
  }

  private void decideMissingShareAsServerAdmin(String operation) throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              mutation { %s(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(operation, UUID.randomUUID()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath("$.data.%s.userErrors[0].__typename".formatted(operation))
                  .value("ShareNotFoundError"));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }
}
