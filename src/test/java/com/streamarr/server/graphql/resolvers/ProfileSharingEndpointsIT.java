package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
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
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The sharing lifecycle through the GraphQL boundary against real PostgreSQL and Cedar: offers and
 * their decisions, activation eligibility, name conflicts, membership-required shares,
 * administratively ending after password confirmation, previews, and visitor-session effects.
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
  @Autowired private JdbcTemplate jdbcTemplate;

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
                share { status } userErrors { __typename ... on MutationError { message } } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.acceptProfileShare.userErrors[0].__typename")
                  .value("OfferInvalidatedError"))
          .andExpect(
              jsonPath("$.data.acceptProfileShare.userErrors[0].message")
                  .value(
                      "This offer was withdrawn (offerer no longer authorized) and can no longer"
                          + " be accepted."));
      assertThat(shareRepository.findById(shareId).orElseThrow().getStatus())
          .isEqualTo(ProfileShareStatus.INVALIDATED);

      // A manager who may view the Profile sees why the offer was withdrawn.
      graphql(
              authTestSupport.accountBearer(owner),
              """
              query { profileShares(profileId: "%s") {
                edges { node { id status invalidationReason } } } }
              """
                  .formatted(orphan.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath(
                  "$.data.profileShares.edges[?(@.node.id == '%s')].node.status".formatted(shareId),
                  contains("INVALIDATED")))
          .andExpect(
              jsonPath(
                  "$.data.profileShares.edges[?(@.node.id == '%s')].node.invalidationReason"
                      .formatted(shareId),
                  contains("offerer no longer authorized")));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName(
      "Should preserve a pending offer when a demoted ServerAdmin still directly manages the"
          + " Profile")
  void shouldPreservePendingOfferWhenDemotedServerAdminStillDirectlyManagesProfile()
      throws Exception {
    var orphan = managedOrphan();
    var revoker = authTestSupport.createAdminIdentity();
    try {
      assertThat(userAccountRepository.tryGrantServerAdmin(owner.account().getId())).isTrue();
      var shareId = offer(orphan, host.household().getId());

      graphql(
              authTestSupport.freshAccountBearer(revoker),
              """
              mutation { revokeServerAdmin(input: {accountId: "%s", reason: "rotation"}) {
                account { serverAdmin } userErrors { __typename } } }
              """
                  .formatted(owner.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.revokeServerAdmin.account.serverAdmin").value(false))
          .andExpect(jsonPath("$.data.revokeServerAdmin.userErrors").isEmpty());

      graphql(
              authTestSupport.accountBearer(host),
              """
              mutation { acceptProfileShare(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"))
          .andExpect(jsonPath("$.data.acceptProfileShare.userErrors").isEmpty());
    } finally {
      authTestSupport.deleteIdentity(revoker);
    }
  }

  @Test
  @DisplayName("Should preserve expiry when an expired offer's offerer is no longer authorized")
  void shouldPreserveExpiryWhenExpiredOffersOffererIsNoLongerAuthorized() throws Exception {
    var orphan = managedOrphan();
    var offerer = authTestSupport.createAdminIdentity();
    try {
      var response =
          graphql(
                  authTestSupport.accountBearer(offerer),
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
      expire(shareId);
      transactionTemplate.executeWithoutResult(
          _ -> {
            var revoked = userAccountRepository.findById(offerer.account().getId()).orElseThrow();
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
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath("$.data.acceptProfileShare.userErrors[0].__typename")
                  .value("ShareNotPendingError"));

      var stored = shareRepository.findById(shareId).orElseThrow();
      assertThat(stored.getStatus()).isEqualTo(ProfileShareStatus.PENDING);
      assertThat(stored.getInvalidationReason()).isEmpty();
    } finally {
      authTestSupport.deleteIdentity(offerer);
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
        raceWhileProfileLocked(
            orphan.getId(),
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

    // The membership-required share answers its typed refusal (Cedar and T3 both refuse it).
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
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.endProfileShare.share").doesNotExist())
        .andExpect(
            jsonPath("$.data.endProfileShare.userErrors[0].__typename")
                .value("MembershipShareCannotEndError"));
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
  @DisplayName(
      "Should require a fresh ServerAdmin and audit when a share is administratively ended")
  void shouldRequireFreshServerAdminAndAuditWhenShareIsAdministrativelyEnded() throws Exception {
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
              mutation { administrativelyEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.administrativelyEndProfileShare.userErrors[0].__typename")
                  .value("ReauthenticationRequiredError"));

      graphql(
              authTestSupport.freshAccountBearer(serverAdmin),
              """
              mutation { administrativelyEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(shareId))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.administrativelyEndProfileShare.share.status").value("ENDED"));

      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT,
                  SECURITY_AUDIT_EVENT.OPERATION.eq("administrativelyEndProfileShare")))
          .isEqualTo(1);
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should audit one winner when two administrative-end requests race")
  void shouldAuditOneWinnerWhenTwoAdministrativelyEndRequestsRace() throws Exception {
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
      var administrativelyEnd =
          """
          mutation { administrativelyEndProfileShare(input: {shareId: "%s", reason: "abuse report"}) {
            share { status } userErrors { __typename } } }
          """
              .formatted(shareId);
      var responses =
          raceWhileShareLocked(
              shareId,
              () ->
                  graphql(bearer, administrativelyEnd)
                      .andExpect(status().isOk())
                      .andReturn()
                      .getResponse()
                      .getContentAsString(),
              () ->
                  graphql(bearer, administrativelyEnd)
                      .andExpect(status().isOk())
                      .andReturn()
                      .getResponse()
                      .getContentAsString());

      var results =
          responses.stream()
              .map(response -> mutationResult(response, "administrativelyEndProfileShare"))
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
                  SECURITY_AUDIT_EVENT,
                  SECURITY_AUDIT_EVENT.OPERATION.eq("administrativelyEndProfileShare")))
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

  private List<String> raceWhileProfileLocked(
      UUID profileId, Callable<String> firstMutation, Callable<String> secondMutation)
      throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var profileLock =
            lockRow(
                RowLockTarget.builder()
                    .dataSource(dataSource)
                    .table("profile")
                    .rowId(profileId)
                    .build())) {
      var first = executor.submit(firstMutation);
      var second = executor.submit(secondMutation);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(waitersBehind(jdbcTemplate, profileLock.backendPid(), "%"))
                      .as("both mutations should wait on the Profile coordination lock")
                      .isEqualTo(2));

      profileLock.release();
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
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

  // ---- A missing Profile answers not-found (or nothing), never "authorization unavailable".

  @Test
  @DisplayName("Should answer profile-not-found when a ServerAdmin offers a missing Profile")
  void shouldAnswerProfileNotFoundWhenServerAdminOffersMissingProfile() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                share { id } userErrors { __typename } } }
              """
                  .formatted(UUID.randomUUID(), host.household().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath("$.data.offerProfileShare.userErrors[0].__typename")
                  .value("ProfileNotFoundError"));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should answer an empty preview when a ServerAdmin previews a missing Profile")
  void shouldAnswerEmptyPreviewWhenServerAdminPreviewsMissingProfile() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              query { profileSharePreview(profileId: "%s", householdId: "%s") {
                wouldLock nameConflict } }
              """
                  .formatted(UUID.randomUUID(), host.household().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.profileSharePreview").doesNotExist());
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should answer an empty page when a ServerAdmin lists shares of a missing Profile")
  void shouldAnswerEmptyPageWhenServerAdminListsSharesOfMissingProfile() throws Exception {
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              query { profileShares(profileId: "%s") { edges { node { id } } } }
              """
                  .formatted(UUID.randomUUID()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.profileShares.edges.length()").value(0));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should invalidate the pending offer when its ServerAdmin offerer is disabled")
  void shouldInvalidatePendingOfferWhenServerAdminOffererIsDisabled() throws Exception {
    var orphan = managedOrphan();
    var offerer = authTestSupport.createAdminIdentity();
    var disabler = authTestSupport.createAdminIdentity();
    try {
      var response =
          graphql(
                  authTestSupport.accountBearer(offerer),
                  """
                  mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                    share { id status } userErrors { __typename } } }
                  """
                      .formatted(orphan.getId(), host.household().getId()))
              .andExpect(status().isOk())
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

      graphql(
              authTestSupport.freshAccountBearer(disabler),
              """
              mutation { disableAccount(input: {accountId: "%s"}) {
                account { enabled } userErrors { __typename } } }
              """
                  .formatted(offerer.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.disableAccount.account.enabled").value(false));

      var stored = shareRepository.findById(shareId).orElseThrow();
      assertThat(stored.getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
      assertThat(stored.getInvalidationReason()).contains("issuer disabled");
    } finally {
      authTestSupport.deleteIdentity(disabler);
      authTestSupport.deleteIdentity(offerer);
    }
  }

  // ---- Expiry is a predicate: a stale PENDING offer is neither listed nor live.

  @Test
  @DisplayName("Should omit an expired offer when a Household lists its pending offers")
  void shouldOmitExpiredOfferWhenHouseholdListsPendingOffers() throws Exception {
    var orphan = managedOrphan();
    expire(UUID.fromString(offer(orphan, host.household().getId())));

    graphql(
            authTestSupport.accountBearer(host),
            """
            query { pendingShareOffers(householdId: "%s") { edges { node { id status } } } }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.pendingShareOffers.edges.length()").value(0));
  }

  @Test
  @DisplayName("Should delete a Profile when its only share is an expired pending offer")
  void shouldDeleteProfileWhenItsOnlyShareIsExpiredPendingOffer() throws Exception {
    var orphan = unsharedManagedProfile();
    transactionTemplate.executeWithoutResult(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(orphan.getId())
                    .householdId(host.household().getId())
                    .status(ProfileShareStatus.PENDING)
                    .offeredByAccountId(owner.account().getId())
                    .expiresAt(Instant.now().minusSeconds(1))
                    .build()));

    graphql(
            authTestSupport.freshAccountBearer(owner),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteProfile.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.deleteProfile.deletedProfileId").value(orphan.getId().toString()));

    assertThat(profileRepository.findById(orphan.getId())).isEmpty();
  }

  private void expire(UUID shareId) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var expired = shareRepository.findById(shareId).orElseThrow();
          expired.setExpiresAt(Instant.now().minusSeconds(1));
          shareRepository.saveAndFlush(expired);
        });
  }

  /** An unlinked Adult Profile the owner solely manages, with no share at all. */
  private Profile unsharedManagedProfile() {
    return transactionTemplate.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(owner.household().getId())
                      .kind(ProfileKind.ADULT)
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(orphan.getId())
                  .build());
          return orphan;
        });
  }

  // ---- T7 from the profile trigger: restricting a hosted Profile answers a typed rejection.

  @Test
  @DisplayName(
      "Should answer a typed rejection when restricting a Profile hosted by an adminless Household")
  void shouldAnswerTypedRejectionWhenRestrictingProfileHostedByAdminlessHousehold()
      throws Exception {
    var orphan = managedOrphan();
    var empty =
        householdRepository.saveAndFlush(HouseholdFixture.defaultHouseholdBuilder().build());
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      accept(serverAdmin, UUID.fromString(offer(orphan, empty.getId())));

      graphql(
              authTestSupport.accountBearer(owner),
              """
              mutation { changeProfileKind(input: {profileId: "%s", kind: KID}) {
                profile { id } userErrors { __typename } } }
              """
                  .formatted(orphan.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.changeProfileKind.profile").doesNotExist())
          .andExpect(
              jsonPath("$.data.changeProfileKind.userErrors[0].__typename")
                  .value("RestrictedProfileRequiresHouseholdAdminError"));

      assertThat(profileRepository.findById(orphan.getId()).orElseThrow().getKind())
          .isEqualTo(ProfileKind.ADULT);
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
      householdRepository.deleteById(empty.getId());
    }
  }

  private void accept(AuthTestSupport.TestIdentity decider, UUID shareId) throws Exception {
    graphql(
            authTestSupport.accountBearer(decider),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"));
  }
}
