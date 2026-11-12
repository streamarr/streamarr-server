package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ProfileHouseholdShare.PROFILE_HOUSEHOLD_SHARE;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SystemActor;
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
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    owner = authTestSupport.createIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(owner);
  }

  @Test
  @DisplayName("Should make a Profile available when its share offer is accepted")
  void shouldMakeProfileAvailableWhenShareOfferIsAccepted() throws Exception {
    var orphan = managedOrphan();

    var shareId = offer(orphan, host);

    graphql(
            authTestSupport.accountBearer(host),
            """
            query { householdShares(householdId: "%s", statuses: [PENDING]) {
              edges { node { id status } } } }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.householdShares.edges.length()").value(1))
        .andExpect(jsonPath("$.data.householdShares.edges[0].node.id").value(shareId));

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
    var code = authTestSupport.shareCodeFor(host);
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
              share { id } userErrors { __typename ... on InputMutationError { inputPath } } } }
            """
                .formatted(orphan.getId(), code))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.offerProfileShare.userErrors[0].__typename")
                .value("ProfileAlreadySharedError"))
        .andExpect(
            jsonPath("$.data.offerProfileShare.userErrors[0].inputPath[0]").value("profileId"));

    // The refused offer rolled back, so it spent nothing: the code still makes another offer.
    offer(managedOrphan(), code);
  }

  @Test
  @DisplayName("Should name the code's Household to the offering side when an offer is made")
  void shouldNameCodesHouseholdToOfferingSideWhenOfferIsMade() throws Exception {
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
              share { status householdId household { id name } } userErrors { __typename } } }
            """
                .formatted(managedOrphan().getId(), authTestSupport.shareCodeFor(host)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"))
        .andExpect(
            jsonPath("$.data.offerProfileShare.share.householdId")
                .value(host.household().getId().toString()))
        .andExpect(
            jsonPath("$.data.offerProfileShare.share.household.name")
                .value(host.household().getName()));
  }

  @Test
  @DisplayName(
      "Should keep naming the code's Household to the offering side and never to the Household's"
          + " HouseholdAdmin when an offer made with a code is declined")
  void shouldKeepNamingCodesHouseholdToOfferingSideAndNeverToHouseholdAdminWhenOfferIsDeclined()
      throws Exception {
    var shareId = offer(managedOrphan(), host);
    var hostsList =
        """
        query { householdShares(householdId: "%s") {
          edges { node { id profile { id } household { name } } } } }
        """
            .formatted(host.household().getId());
    graphql(authTestSupport.accountBearer(host), hostsList)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath(offerIn("householdShares", shareId) + ".node.profile.id").isNotEmpty())
        .andExpect(
            jsonPath(
                offerIn("householdShares", shareId) + ".node.household", contains(nullValue())));

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { rejectProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));

    graphql(authTestSupport.accountBearer(host), hostsList)
        .andExpect(status().isOk())
        .andExpect(jsonPath(offerIn("householdShares", shareId)).isEmpty());
    var offered = shareRepository.findById(UUID.fromString(shareId)).orElseThrow();
    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileShares(profileId: "%s", statuses: [REJECTED]) {
              edges { node { id household { name } } } } }
            """
                .formatted(offered.getProfileId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath(
                offerIn("profileShares", shareId) + ".node.household.name",
                contains(host.household().getName())));
  }

  private static String offerIn(String list, String shareId) {
    return "$.data.%s.edges[?(@.node.id == '%s')]".formatted(list, shareId);
  }

  @Test
  @DisplayName("Should refuse an offer, naming no Household, when its code was already spent")
  void shouldRefuseOfferNamingNoHouseholdWhenItsCodeWasAlreadySpent() throws Exception {
    var code = authTestSupport.shareCodeFor(host);
    offer(managedOrphan(), code);

    var refused =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                  share { id }
                  userErrors {
                    __typename ... on InputMutationError { message inputPath } } } }
                """
                    .formatted(managedOrphan().getId(), code))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.offerProfileShare.share").doesNotExist())
            .andExpect(
                jsonPath("$.data.offerProfileShare.userErrors[0].__typename")
                    .value("ShareCodeRefusedError"))
            .andExpect(
                jsonPath("$.data.offerProfileShare.userErrors[0].inputPath[0]").value("shareCode"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(refused)
        .doesNotContain(host.household().getName())
        .doesNotContain(host.household().getId().toString());
    assertThat(shareRepository.findAll())
        .filteredOn(share -> share.getHouseholdId().equals(host.household().getId()))
        .filteredOn(share -> share.getStatus() == ProfileShareStatus.PENDING)
        .hasSize(1);
  }

  @Test
  @DisplayName("Should refuse an offer as forbidden when a device-bound session makes it")
  void shouldRefuseOfferAsForbiddenWhenDeviceBoundSessionMakesIt() throws Exception {
    var code = authTestSupport.shareCodeFor(host);
    graphql(
            authTestSupport.deviceBoundProfileBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
              share { id } userErrors { __typename } } }
            """
                .formatted(owner.profile().getId(), code))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    // Nothing was presented, so the code still makes the offer.
    offer(managedOrphan(), code);
  }

  @Test
  @DisplayName("Should cancel the older offer when a pending offer is replaced")
  void shouldCancelOlderOfferWhenPendingOfferIsReplaced() throws Exception {
    var orphan = managedOrphan();
    var firstShareId = offer(orphan, host);

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
              share { id status } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), authTestSupport.shareCodeFor(host)))
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
    var firstShareId = UUID.fromString(offer(orphan, host));
    transactionTemplate.executeWithoutResult(
        _ -> {
          var expired = shareRepository.findById(firstShareId).orElseThrow();
          expired.setExpiresAt(Instant.now().minusSeconds(1));
          shareRepository.saveAndFlush(expired);
        });

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
              share { id status } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), authTestSupport.shareCodeFor(host)))
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
                  mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                    share { id status } userErrors { __typename } } }
                  """
                      .formatted(orphan.getId(), authTestSupport.shareCodeFor(host)))
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
                      "This share invitation was withdrawn and can't be accepted. Reload the page"
                          + " to see if other share invitations are available."));
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
      assertThat(
              transactionTemplate.<Boolean>execute(
                  _ -> userAccountRepository.tryGrantServerAdmin(owner.account().getId())))
          .isTrue();
      var shareId = offer(orphan, host);

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
    // A ServerAdmin still finds an offer that is over and may decide it.
    var decider = authTestSupport.createAdminIdentity();
    try {
      var response =
          graphql(
                  authTestSupport.accountBearer(offerer),
                  """
                  mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                    share { id status } userErrors { __typename } } }
                  """
                      .formatted(orphan.getId(), authTestSupport.shareCodeFor(host)))
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
              authTestSupport.accountBearer(decider),
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
      authTestSupport.deleteIdentity(decider);
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
      // ServerAdmin may issue the code and accept on the Household's behalf, but the Household
      // still needs an eligible HouseholdAdmin.
      var serverAdmin = authTestSupport.createAdminIdentity();
      try {
        var shareId = offer(kid, authTestSupport.shareCodeFor(serverAdmin, empty.getId()));

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
    var shareId = offer(twin, host);

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
  @DisplayName(
      "Should answer a declined offer as missing when the Household it was offered to asks before"
          + " and after its Profile is deleted")
  void shouldAnswerDeclinedOfferAsMissingWhenTargetHouseholdAsksBeforeAndAfterItsProfileIsDeleted()
      throws Exception {
    var orphan = unsharedManagedProfile();
    var shareId = offer(orphan, host);
    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { rejectProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));

    var whileKept = hostAnswersFor(shareId);
    graphql(
            authTestSupport.freshAccountBearer(owner),
            """
            mutation { deleteProfile(input: {profileId: "%s"}) {
              deletedProfileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteProfile.deletedProfileId").value(orphan.getId().toString()));
    var afterDeletion = hostAnswersFor(shareId);

    // The Household no longer sees the share, so nothing it asks tells whether the Profile is gone.
    assertThat(whileKept)
        .containsOnly(
            "ShareNotFoundError", "ShareNotFoundError", "ShareNotFoundError", "ShareNotFoundError");
    assertThat(afterDeletion).isEqualTo(whileKept);
  }

  @Test
  @DisplayName(
      "Should answer a share as missing when a HouseholdAdmin who only hosts its Profile changes"
          + " it, whether it is live, over, structural or not there at all")
  void shouldAnswerShareAsMissingWhenHouseholdAdminWhoOnlyHostsItsProfileChangesIt()
      throws Exception {
    var visitor = owner.profile().getId();
    var elsewhere = authTestSupport.createIdentity();
    try {
      var offered =
          savedShare(
              ProfileHouseholdShare.builder()
                  .profileId(visitor)
                  .householdId(elsewhere.household().getId())
                  .status(ProfileShareStatus.PENDING)
                  .offeredByAccountId(owner.account().getId())
                  .expiresAt(Instant.now().plus(Duration.ofDays(1))));
      var declined =
          savedShare(
              ProfileHouseholdShare.builder()
                  .profileId(visitor)
                  .householdId(elsewhere.household().getId())
                  .status(ProfileShareStatus.REJECTED)
                  .offeredByAccountId(owner.account().getId()));
      savedShare(
          ProfileHouseholdShare.builder()
              .profileId(visitor)
              .householdId(host.household().getId())
              .status(ProfileShareStatus.ACTIVE));
      var membership =
          shareRepository
              .findByProfileIdAndHouseholdIdAndStatus(
                  visitor, owner.household().getId(), ProfileShareStatus.ACTIVE)
              .orElseThrow()
              .getId()
              .toString();
      var missing = hostAnswersFor(UUID.randomUUID().toString());

      assertThat(missing).containsOnly("ShareNotFoundError");
      assertThat(
              List.of(
                  hostAnswersFor(offered), hostAnswersFor(declined), hostAnswersFor(membership)))
          .containsOnly(missing);
    } finally {
      authTestSupport.deleteIdentity(elsewhere);
    }
  }

  @Test
  @DisplayName(
      "Should answer a share as missing when its offerer stopped managing the Profile and cancels"
          + " it, whether it is active, over or not there at all")
  void shouldAnswerShareAsMissingWhenOffererWhoStoppedManagingProfileCancelsIt() throws Exception {
    var orphan = managedOrphan();
    var coManager = authTestSupport.createHouseholdMemberIdentity(owner);
    var elsewhere = authTestSupport.createIdentity();
    try {
      transactionTemplate.executeWithoutResult(
          _ ->
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(coManager.account().getId())
                      .profileId(orphan.getId())
                      .build()));
      var active = offer(orphan, host);
      accept(host, UUID.fromString(active));
      var ended = offer(orphan, elsewhere);
      accept(elsewhere, UUID.fromString(ended));
      graphql(
              authTestSupport.accountBearer(elsewhere),
              """
              mutation { endProfileShare(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(ended))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.endProfileShare.share.status").value("ENDED"));
      graphql(
              authTestSupport.accountBearer(owner),
              """
              mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
                profileId userErrors { __typename } } }
              """
                  .formatted(orphan.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.relinquishProfileManagement.userErrors").isEmpty());

      var answers =
          List.of(
              answerOf(owner, "cancelProfileShare", active),
              answerOf(owner, "cancelProfileShare", ended),
              answerOf(owner, "cancelProfileShare", UUID.randomUUID().toString()));

      assertThat(answers)
          .containsExactly("ShareNotFoundError", "ShareNotFoundError", "ShareNotFoundError");
      assertThat(shareRepository.findById(UUID.fromString(active)).orElseThrow().getStatus())
          .isEqualTo(ProfileShareStatus.ACTIVE);
    } finally {
      authTestSupport.deleteIdentity(elsewhere);
      authTestSupport.deleteIdentity(coManager);
    }
  }

  private String savedShare(ProfileHouseholdShare.ProfileHouseholdShareBuilder<?, ?> share) {
    return transactionTemplate
        .execute(_ -> shareRepository.saveAndFlush(share.build()))
        .getId()
        .toString();
  }

  /** What the host's HouseholdAdmin gets from every verb on the share, in one fixed order. */
  private List<String> hostAnswersFor(String shareId) throws Exception {
    var answers = new ArrayList<String>();
    for (var verb :
        List.of(
            "acceptProfileShare", "rejectProfileShare", "cancelProfileShare", "endProfileShare")) {
      answers.add(answerOf(host, verb, shareId));
    }

    return answers;
  }

  /** What one verb on the share answers the caller: the type of its first error, or FORBIDDEN. */
  private String answerOf(AuthTestSupport.TestIdentity caller, String verb, String shareId)
      throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(caller),
                """
                mutation { %s(input: {shareId: "%s"}) { userErrors { __typename } } }
                """
                    .formatted(verb, shareId))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(response)
        .path("data")
        .path(verb)
        .path("userErrors")
        .path(0)
        .path("__typename")
        .asString("FORBIDDEN");
  }

  @Test
  @DisplayName("Should return not-pending when another decision has already won")
  void shouldReturnNotPendingWhenAnotherDecisionAlreadyWon() throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host);

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
              share { status } userErrors { __typename ... on MutationError { message } } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.cancelProfileShare.userErrors[0].__typename")
                .value("ShareNotPendingError"))
        .andExpect(
            jsonPath("$.data.cancelProfileShare.userErrors[0].message")
                .value(
                    "This share invitation can't be canceled anymore. Reload the page to see what"
                        + " changed."));
  }

  @Test
  @DisplayName("Should allow one decision when acceptance and rejection race")
  void shouldAllowOneDecisionWhenAcceptanceAndRejectionRace() throws Exception {
    var orphan = managedOrphan();
    var shareId = UUID.fromString(offer(orphan, host));
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
    var decided = shareRepository.findById(shareId).orElseThrow().getStatus();
    assertThat(results).extracting(MutationResult::accepted).containsExactlyInAnyOrder(true, false);
    assertThat(decided).isIn(ProfileShareStatus.ACTIVE, ProfileShareStatus.REJECTED);
    // The loser answers as the share now reads to the Household: an active share that is no longer
    // pending, or a rejected offer that it no longer sees at all.
    assertThat(results)
        .filteredOn(result -> !result.accepted())
        .extracting(MutationResult::errorType)
        .containsExactly(
            decided == ProfileShareStatus.ACTIVE ? "ShareNotPendingError" : "ShareNotFoundError");
  }

  @Test
  @DisplayName(
      "Should answer share-not-found when the HouseholdAdmin's end loses to another end of the"
          + " share")
  void shouldAnswerShareNotFoundWhenHouseholdAdminsEndLosesToAnotherEnd() throws Exception {
    var orphan = managedOrphan();
    var shareId = UUID.fromString(offer(orphan, host));
    accept(host, shareId);
    var bearer = authTestSupport.accountBearer(host);
    Callable<String> end =
        () ->
            graphql(
                    bearer,
                    """
                    mutation { endProfileShare(input: {shareId: "%s"}) {
                      share { status } userErrors { __typename } } }
                    """
                        .formatted(shareId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

    var results =
        raceWhileShareLocked(shareId, end, end).stream()
            .map(response -> mutationResult(response, "endProfileShare"))
            .toList();

    assertThat(results).extracting(MutationResult::accepted).containsExactlyInAnyOrder(true, false);
    // The losing end finds the share over, and the Household no longer sees a share that is over.
    assertThat(results)
        .filteredOn(result -> !result.accepted())
        .extracting(MutationResult::errorType)
        .containsExactly("ShareNotFoundError");
  }

  @Test
  @DisplayName(
      "Should answer share-not-found when the HouseholdAdmin's rejection waits while the offer is"
          + " deleted")
  void shouldAnswerShareNotFoundWhenHouseholdAdminsRejectionWaitsWhileOfferIsDeleted()
      throws Exception {
    var orphan = managedOrphan();
    var shareId = UUID.fromString(offer(orphan, host));
    var bearer = authTestSupport.accountBearer(host);

    var response =
        deleteWhileWaiting(
            "profile_household_share",
            shareId,
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

    assertThat(mutationResult(response, "rejectProfileShare").errorType())
        .as("rejection in %s", response)
        .isEqualTo("ShareNotFoundError");
  }

  @Test
  @DisplayName(
      "Should answer share-not-found when an unrelated Account's acceptance waits while the"
          + " Profile is deleted")
  void shouldAnswerShareNotFoundWhenUnrelatedAccountsAcceptanceWaitsWhileProfileIsDeleted()
      throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host);
    var stranger = authTestSupport.createIdentity();
    try {
      var bearer = authTestSupport.accountBearer(stranger);

      var response =
          deleteWhileWaiting(
              "profile",
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
                      .getContentAsString());

      // It may not read the share, so it gets the answer a share that never existed gets.
      assertThat(mutationResult(response, "acceptProfileShare").errorType())
          .as("acceptance in %s", response)
          .isEqualTo("ShareNotFoundError");
    } finally {
      authTestSupport.deleteIdentity(stranger);
    }
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

    // Authorization and database constraints both prevent ending a share required by membership.
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
  @DisplayName("Should forbid ending a share when an unrelated ServerAdmin ends it ordinarily")
  void shouldForbidEndingShareWhenUnrelatedServerAdminEndsItOrdinarily() throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host);
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
    var shareId = offer(orphan, host);
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
    var shareId = UUID.fromString(offer(orphan, host));
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
    var shareId = offer(kid, host);
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
    var code = authTestSupport.shareCodeFor(host);
    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileSharePreview(profileId: "%s", shareCode: "%s") { wouldLock nameConflict } }
            """
                .formatted(owner.profile().getId(), code))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileSharePreview.wouldLock").value(true))
        .andExpect(jsonPath("$.data.profileSharePreview.nameConflict").value(false));

    // The preview spends nothing: the same code still names the Household.
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { lookUpShareCode(input: {code: "%s"}) { householdName userErrors { __typename } } }
            """
                .formatted(code))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.lookUpShareCode.householdName").value(host.household().getName()));
  }

  @Test
  @DisplayName("Should answer no preview and name no Household when the share code does not work")
  void shouldAnswerNoPreviewAndNameNoHouseholdWhenShareCodeDoesNotWork() throws Exception {
    graphql(
            authTestSupport.accountBearer(owner),
            """
            query { profileSharePreview(profileId: "%s", shareCode: "BCDF-GHJK") {
              wouldLock nameConflict } }
            """
                .formatted(owner.profile().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileSharePreview").doesNotExist());
  }

  @Test
  @DisplayName("Should refuse a preview as forbidden when a device-bound session asks for one")
  void shouldRefusePreviewAsForbiddenWhenDeviceBoundSessionAsksForOne() throws Exception {
    graphql(
            authTestSupport.deviceBoundProfileBearer(owner),
            """
            query { profileSharePreview(profileId: "%s", shareCode: "%s") {
              wouldLock nameConflict } }
            """
                .formatted(owner.profile().getId(), authTestSupport.shareCodeFor(host)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.data.profileSharePreview").doesNotExist());
  }

  @Test
  @DisplayName("Should return the final share when last is supplied without before")
  void shouldReturnFinalShareWhenLastIsSuppliedWithoutBefore() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host);
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
    offer(orphan, host);
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
  @DisplayName(
      "Should reject a share cursor when it continues another Profile's or Household's list")
  void shouldRejectAShareCursorWhenItContinuesAnotherProfilesOrHouseholdsList() throws Exception {
    var profileCursor =
        firstCursor(owner, "profileShares(profileId: \"%s\")".formatted(managedOrphan().getId()));
    var householdCursor =
        firstCursor(
            owner, "householdShares(householdId: \"%s\")".formatted(owner.household().getId()));

    assertCursorRefused(
        owner,
        "profileShares(profileId: \"%s\", after: \"%s\")"
            .formatted(managedOrphan().getId(), profileCursor));
    assertCursorRefused(
        host,
        "householdShares(householdId: \"%s\", after: \"%s\")"
            .formatted(host.household().getId(), profileCursor));
    assertCursorRefused(
        host,
        "householdShares(householdId: \"%s\", after: \"%s\")"
            .formatted(host.household().getId(), householdCursor));
  }

  private String firstCursor(AuthTestSupport.TestIdentity reader, String list) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(reader),
                "query { list: %s { edges { cursor } } }".formatted(list))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cursor = objectMapper.readTree(response).at("/data/list/edges/0/cursor").asString("");
    assertThat(cursor).as("first cursor of %s", list).isNotEmpty();
    return cursor;
  }

  private void assertCursorRefused(AuthTestSupport.TestIdentity reader, String list)
      throws Exception {
    graphql(
            authTestSupport.accountBearer(reader),
            "query { %s { edges { node { id } } } }".formatted(list))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
  }

  @Test
  @DisplayName("Should reject a Household share cursor when it continues other statuses")
  void shouldRejectHouseholdShareCursorWhenItContinuesOtherStatuses() throws Exception {
    offer(managedOrphan(), host);
    var pendingPage =
        graphql(
                authTestSupport.accountBearer(host),
                """
                query { householdShares(householdId: "%s", statuses: [PENDING]) {
                  edges { cursor } } }
                """
                    .formatted(host.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cursor =
        objectMapper
            .readTree(pendingPage)
            .path("data")
            .path("householdShares")
            .path("edges")
            .path(0)
            .path("cursor")
            .asString();

    graphql(
            authTestSupport.accountBearer(host),
            """
            query { householdShares(householdId: "%s", statuses: [ACTIVE], after: "%s") {
              edges { node { id } } } }
            """
                .formatted(host.household().getId(), cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
  }

  @Test
  @DisplayName("Should keep only visitors' shares when a Household's shares are kept to visitors")
  void shouldKeepOnlyVisitorsSharesWhenHouseholdsSharesAreKeptToVisitors() throws Exception {
    var visitId = offer(managedOrphan(), host);
    var hostsOwnId =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                host.profile().getId(), host.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow()
            .getId()
            .toString();

    graphql(
            authTestSupport.accountBearer(host),
            """
            query {
              visitors: householdShares(householdId: "%1$s", visitorsOnly: true) {
                edges { node { id visitor } } }
              every: householdShares(householdId: "%1$s", visitorsOnly: false) {
                edges { node { id visitor } } }
              unfiltered: householdShares(householdId: "%1$s") { edges { node { id } } }
            }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.visitors.edges[*].node.id", contains(visitId)))
        .andExpect(jsonPath("$.data.visitors.edges[*].node.visitor", contains(true)))
        .andExpect(
            jsonPath("$.data.every.edges[*].node.id", containsInAnyOrder(visitId, hostsOwnId)))
        .andExpect(
            jsonPath(
                "$.data.every.edges[?(@.node.id == '%s')].node.visitor".formatted(hostsOwnId),
                contains(false)))
        .andExpect(
            jsonPath(
                "$.data.unfiltered.edges[*].node.id", containsInAnyOrder(visitId, hostsOwnId)));
  }

  @Test
  @DisplayName(
      "Should reject a Household share cursor when it continues the list without its visitor"
          + " filter")
  void shouldRejectHouseholdShareCursorWhenItContinuesListWithoutItsVisitorFilter()
      throws Exception {
    offer(managedOrphan(), host);
    var visitorsPage =
        graphql(
                authTestSupport.accountBearer(host),
                """
                query { householdShares(householdId: "%s", visitorsOnly: true) {
                  edges { cursor } } }
                """
                    .formatted(host.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var cursor =
        objectMapper.readTree(visitorsPage).at("/data/householdShares/edges/0/cursor").asString();

    graphql(
            authTestSupport.accountBearer(host),
            """
            query { householdShares(householdId: "%s", after: "%s") { edges { node { id } } } }
            """
                .formatted(host.household().getId(), cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("INVALID_CURSOR"));
  }

  @Test
  @DisplayName("Should keep only the live shares when a Profile's shares are listed as live")
  void shouldKeepOnlyLiveSharesWhenProfileSharesAreListedAsLive() throws Exception {
    var orphan = managedOrphan();
    var offered = offer(orphan, host);
    var declinedHost = authTestSupport.createIdentity();
    try {
      var declined = offer(orphan, declinedHost);
      graphql(
              authTestSupport.accountBearer(declinedHost),
              """
              mutation { rejectProfileShare(input: {shareId: "%s"}) {
                share { status } userErrors { __typename } } }
              """
                  .formatted(declined))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));

      graphql(
              authTestSupport.accountBearer(owner),
              """
              query {
                live: profileShares(profileId: "%1$s", statuses: [PENDING, ACTIVE]) {
                  edges { node { id status } } }
                every: profileShares(profileId: "%1$s") { edges { node { id } } }
              }
              """
                  .formatted(orphan.getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.live.edges.length()").value(2))
          .andExpect(
              jsonPath(
                  "$.data.live.edges[?(@.node.id == '%s')].node.status".formatted(offered),
                  contains("PENDING")))
          .andExpect(
              jsonPath("$.data.live.edges[?(@.node.id == '%s')]".formatted(declined)).isEmpty())
          .andExpect(jsonPath("$.data.every.edges.length()").value(3));
    } finally {
      authTestSupport.deleteIdentity(declinedHost);
    }
  }

  @Test
  @DisplayName(
      "Should keep a declined offer for a ServerAdmin and not for the Household's HouseholdAdmin"
          + " when they list its shares in every status")
  void shouldKeepDeclinedOfferForServerAdminAndNotForHouseholdAdminWhenTheyListEveryStatus()
      throws Exception {
    var shareId = offer(managedOrphan(), host);
    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { rejectProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rejectProfileShare.share.status").value("REJECTED"));
    var lists =
        """
        query {
          every: householdShares(householdId: "%1$s") { edges { node { id status } } }
          live: householdShares(householdId: "%1$s", statuses: [PENDING, ACTIVE]) {
            edges { node { id status } } }
          declined: householdShares(householdId: "%1$s", statuses: [REJECTED]) {
            edges { node { id } } }
        }
        """
            .formatted(host.household().getId());
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(authTestSupport.accountBearer(serverAdmin), lists)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath(
                  "$.data.every.edges[?(@.node.id == '%s')].node.status".formatted(shareId),
                  contains("REJECTED")))
          .andExpect(jsonPath("$.data.live.edges[*].node.status", everyItem(is("ACTIVE"))))
          .andExpect(jsonPath("$.data.declined.edges[*].node.id", contains(shareId)));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }

    // The Household's HouseholdAdmins list only offers still pending and active shares.
    graphql(authTestSupport.accountBearer(host), lists)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.every.edges[*].node.status", everyItem(is("ACTIVE"))))
        .andExpect(
            jsonPath("$.data.every.edges[?(@.node.id == '%s')]".formatted(shareId)).isEmpty())
        .andExpect(jsonPath("$.data.live.edges[*].node.status", everyItem(is("ACTIVE"))))
        .andExpect(jsonPath("$.data.declined.edges").isEmpty());
  }

  @Test
  @DisplayName("Should answer an empty list when a member of the Household lists its shares")
  void shouldAnswerEmptyListWhenMemberOfHouseholdListsItsShares() throws Exception {
    offer(managedOrphan(), host);
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    graphql(
            authTestSupport.accountBearer(member),
            """
            query { householdShares(householdId: "%s") { edges { node { id } } } }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.householdShares.edges").isEmpty());
  }

  @Test
  @DisplayName("Should return the next share when an after cursor is supplied")
  void shouldReturnNextShareWhenAfterCursorIsSupplied() throws Exception {
    var orphan = managedOrphan();
    offer(orphan, host);
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

  /**
   * Runs the mutation while this test holds the lock of one row of the table, then deletes the row
   * under that lock and commits, so the mutation wakes to find it gone.
   */
  private String deleteWhileWaiting(String table, UUID rowId, Callable<String> mutation)
      throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var lock =
          connection.prepareStatement(
              "SELECT id FROM %s WHERE id = ? FOR UPDATE".formatted(table))) {
        lock.setObject(1, rowId);
        try (var rows = lock.executeQuery()) {
          assertThat(rows.next()).as("%s row %s exists", table, rowId).isTrue();
        }
      }

      var holderPid = backendPid(connection);
      var waiting = executor.submit(mutation);
      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(
              () ->
                  assertThat(waitersBehind(jdbcTemplate, holderPid, "%"))
                      .as("the mutation should wait on the %s row", table)
                      .isEqualTo(1));
      try (var actor =
              connection.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)");
          var deletion =
              connection.prepareStatement("DELETE FROM %s WHERE id = ?".formatted(table))) {
        actor.setString(1, SystemActor.ID.toString());
        actor.execute();
        deletion.setObject(1, rowId);
        assertThat(deletion.executeUpdate()).isEqualTo(1);
      }

      connection.commit();
      return waiting.get(10, TimeUnit.SECONDS);
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

  // The list order: newest first, then ID descending.
  private List<UUID> orderedShareIds(UUID profileId) {
    return dsl.select(PROFILE_HOUSEHOLD_SHARE.ID)
        .from(PROFILE_HOUSEHOLD_SHARE)
        .where(PROFILE_HOUSEHOLD_SHARE.PROFILE_ID.eq(profileId))
        .orderBy(PROFILE_HOUSEHOLD_SHARE.CREATED_ON.desc(), PROFILE_HOUSEHOLD_SHARE.ID.desc())
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

  /** The owner offers the Profile with a code that the target HouseholdAdmin issues for it. */
  private String offer(Profile profile, AuthTestSupport.TestIdentity target) throws Exception {
    return offer(profile, authTestSupport.shareCodeFor(target));
  }

  private String offer(Profile profile, String shareCode) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                  share { id status } userErrors { __typename } } }
                """
                    .formatted(profile.getId(), shareCode))
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
              mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                share { id } userErrors { __typename } } }
              """
                  .formatted(UUID.randomUUID(), authTestSupport.shareCodeFor(host)))
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
              query { profileSharePreview(profileId: "%s", shareCode: "%s") {
                wouldLock nameConflict } }
              """
                  .formatted(UUID.randomUUID(), authTestSupport.shareCodeFor(host)))
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
                  mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                    share { id status } userErrors { __typename } } }
                  """
                      .formatted(orphan.getId(), authTestSupport.shareCodeFor(host)))
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
      assertThat(stored.getInvalidationReason()).contains("offerer no longer authorized");
    } finally {
      authTestSupport.deleteIdentity(disabler);
      authTestSupport.deleteIdentity(offerer);
    }
  }

  // ---- Expiry is a predicate: a stale PENDING offer is listed as EXPIRED and is not live.

  @Test
  @DisplayName(
      "Should list an expired offer as expired for a ServerAdmin and not for the Household's"
          + " HouseholdAdmin when they list its shares")
  void shouldListExpiredOfferAsExpiredForServerAdminAndNotForHouseholdAdminWhenTheyListItsShares()
      throws Exception {
    var orphan = managedOrphan();
    var shareId = offer(orphan, host);
    expire(UUID.fromString(shareId));
    var lists =
        """
        query {
          pending: householdShares(householdId: "%1$s", statuses: [PENDING]) {
            edges { node { id } } }
          expired: householdShares(householdId: "%1$s", statuses: [EXPIRED]) {
            edges { node { id status } } }
        }
        """
            .formatted(host.household().getId());
    var serverAdmin = authTestSupport.createAdminIdentity();
    try {
      graphql(authTestSupport.accountBearer(serverAdmin), lists)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.pending.edges.length()").value(0))
          .andExpect(jsonPath("$.data.expired.edges[*].node.id", contains(shareId)))
          .andExpect(jsonPath("$.data.expired.edges[*].node.status", contains("EXPIRED")));
    } finally {
      authTestSupport.deleteIdentity(serverAdmin);
    }

    // An offer past its expiry is no longer pending, so it leaves the HouseholdAdmin's list.
    graphql(authTestSupport.accountBearer(host), lists)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.pending.edges.length()").value(0))
        .andExpect(jsonPath("$.data.expired.edges.length()").value(0));
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

  // Restricting a hosted Profile without an eligible HouseholdAdmin returns a typed rejection.

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
      accept(
          serverAdmin,
          UUID.fromString(offer(orphan, authTestSupport.shareCodeFor(serverAdmin, empty.getId()))));

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
