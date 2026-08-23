package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.ProfileManagerInvitation.PROFILE_MANAGER_INVITATION;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.awaitility.Awaitility;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The direct-manager lifecycle through the GraphQL boundary against real PostgreSQL and Cedar:
 * invitation and consent (the code appears exactly once), one winner per transition, the eligible
 * manager rule on relinquishing, and the fresh-reauthenticated override killing restorable
 * proposals.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Manager Endpoints Integration Tests")
class ProfileManagerEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity recipient;

  @BeforeEach
  void setUp() {
    owner = authTestSupport.createAdminIdentity();
    recipient = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    authTestSupport.deleteIdentity(recipient);
    authTestSupport.deleteIdentity(owner);
  }

  @Test
  @DisplayName("Should grant management when the named recipient accepts an invitation")
  void shouldGrantManagementWhenNamedRecipientAcceptsInvitation() throws Exception {
    var orphan = managedOrphan();

    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { inviteProfileManager(input: {profileId: "%s",
                  recipientAccountId: "%s"}) {
                  issued { code invitation { id profileName status } }
                  userErrors { __typename } } }
                """
                    .formatted(orphan.getId(), recipient.account().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.inviteProfileManager.issued.invitation.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var issued = objectMapper.readTree(response).path("data").path("inviteProfileManager");
    var code = issued.path("issued").path("code").asString();
    var invitationId = issued.path("issued").path("invitation").path("id").asString();

    graphql(
            authTestSupport.accountBearer(recipient),
            "query { pendingManagerInvitations { edges { node { id profileName } } } }")
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.pendingManagerInvitations.edges[0].node.id").value(invitationId));

    graphql(
            authTestSupport.accountBearer(recipient),
            """
            mutation { acceptManagerInvitation(input: {code: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(code))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.acceptManagerInvitation.invitation.status").value("ACCEPTED"));

    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), orphan.getId()))
        .isTrue();

    // The consumed code answers exactly like an unknown one; the loser transition refuses.
    graphql(
            authTestSupport.accountBearer(recipient),
            """
            mutation { acceptManagerInvitation(input: {code: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(code))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.acceptManagerInvitation.userErrors[0].__typename")
                .value("ManagerInvitationNotFoundError"));
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { cancelManagerInvitation(input: {invitationId: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(invitationId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.cancelManagerInvitation.userErrors[0].__typename")
                .value("InvitationNotPendingError"));
  }

  @Test
  @DisplayName("Should hide manager invitations when the caller only supervises by share")
  void shouldHideManagerInvitationsWhenCallerOnlySupervisesByShare() throws Exception {
    var namedRecipientId = transactionTemplate.execute(_ -> secondLocalManagerId());
    var invitation =
        transactionTemplate.execute(
            _ -> {
              var kid =
                  profileRepository.saveAndFlush(
                      ProfileFixture.kidProfileBuilder()
                          .householdId(owner.household().getId())
                          .name("Visiting Kid")
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(owner.account().getId())
                      .profileId(kid.getId())
                      .build());
              shareRepository.saveAndFlush(
                  ProfileHouseholdShare.builder()
                      .profileId(kid.getId())
                      .householdId(owner.household().getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              shareRepository.saveAndFlush(
                  ProfileHouseholdShare.builder()
                      .profileId(kid.getId())
                      .householdId(recipient.household().getId())
                      .status(ProfileShareStatus.ACTIVE)
                      .build());
              return invitationRepository.saveAndFlush(
                  ProfileManagerInvitation.builder()
                      .profileId(kid.getId())
                      .profileName(kid.getName())
                      .inviterAccountId(owner.account().getId())
                      .inviterDisplayName(owner.account().getDisplayName())
                      .recipientAccountId(namedRecipientId)
                      .recipientEmail("named-recipient@example.com")
                      .expiresAt(Instant.now().plusSeconds(3600))
                      .publicId(UUID.randomUUID().toString())
                      .secretDigest(new byte[] {1})
                      .build());
            });

    graphql(
            authTestSupport.accountBearer(recipient),
            """
            query { managerInvitations(profileId: "%s") {
              edges { node { id recipientEmail } } } }
            """
                .formatted(invitation.getProfileId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.managerInvitations.edges").isEmpty());
  }

  @Test
  @DisplayName(
      "Should default backward pagination to one hundred when only a before cursor is provided")
  void shouldDefaultBackwardPaginationToOneHundredWhenOnlyBeforeCursorIsProvided()
      throws Exception {
    persistPendingInvitations(102);

    var firstPage =
        graphql(
                authTestSupport.accountBearer(recipient),
                """
                query { pendingManagerInvitations(first: 102) {
                  edges { cursor node { id } } } }
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.pendingManagerInvitations.edges.length()").value(102))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var edges =
        objectMapper
            .readTree(firstPage)
            .path("data")
            .path("pendingManagerInvitations")
            .path("edges");
    var before = edges.get(edges.size() - 1).path("cursor").asString();

    graphql(
            authTestSupport.accountBearer(recipient),
            """
            query { pendingManagerInvitations(before: "%s") {
              edges { node { id } } } }
            """
                .formatted(before))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.pendingManagerInvitations.edges.length()").value(100));
  }

  @Test
  @DisplayName("Should decline an invitation when the named recipient acts")
  void shouldDeclineInvitationWhenNamedRecipientActs() throws Exception {
    var orphan = managedOrphan();
    var declining =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { inviteProfileManager(input: {profileId: "%s",
                  recipientAccountId: "%s"}) {
                  issued { code } userErrors { __typename } } }
                """
                    .formatted(orphan.getId(), recipient.account().getId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var code =
        objectMapper
            .readTree(declining)
            .path("data")
            .path("inviteProfileManager")
            .path("issued")
            .path("code")
            .asString();

    graphql(
            authTestSupport.accountBearer(recipient),
            """
            mutation { declineManagerInvitation(input: {code: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(code))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.declineManagerInvitation.invitation.status").value("DECLINED"));

    graphql(
            authTestSupport.accountBearer(recipient),
            """
            mutation { declineManagerInvitation(input: {code: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(code))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.declineManagerInvitation.userErrors[0].__typename")
                .value("ManagerInvitationNotFoundError"));
  }

  @Test
  @DisplayName("Should remove a direct manager when the sovereign account acts")
  void shouldRemoveDirectManagerWhenSovereignAccountActs() throws Exception {
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(recipient.account().getId())
                    .profileId(owner.account().getPersonalProfileId())
                    .build()));
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { removeProfileManager(input: {profileId: "%s", accountId: "%s"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(owner.account().getPersonalProfileId(), recipient.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.removeProfileManager.userErrors").isEmpty());
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), owner.account().getPersonalProfileId()))
        .isFalse();
  }

  @Test
  @DisplayName("Should let exactly one decision win when acceptance and decline race")
  void shouldLetExactlyOneDecisionWinWhenAcceptanceAndDeclineRace() throws Exception {
    var orphan = managedOrphan();
    var response =
        graphql(
                authTestSupport.accountBearer(owner),
                """
                mutation { inviteProfileManager(input: {profileId: "%s",
                  recipientAccountId: "%s"}) {
                  issued { code invitation { id } } userErrors { __typename } } }
                """
                    .formatted(orphan.getId(), recipient.account().getId()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var issued = objectMapper.readTree(response).at("/data/inviteProfileManager/issued");
    var code = issued.path("code").asString();
    var bearer = authTestSupport.accountBearer(recipient);

    var outcomes =
        raceGraphql(
            new GraphqlRaceLock(RaceLockTarget.INVITATION, persistedInvitationId(issued)),
            new ConcurrentGraphqlCall(
                bearer,
                """
                mutation { acceptManagerInvitation(input: {code: "%s"}) {
                  invitation { status } userErrors { __typename } } }
                """
                    .formatted(code)),
            new ConcurrentGraphqlCall(
                bearer,
                """
                mutation { declineManagerInvitation(input: {code: "%s"}) {
                  invitation { status } userErrors { __typename } } }
                """
                    .formatted(code)));

    var accepted =
        countText(outcomes, "/data/acceptManagerInvitation/invitation/status", "ACCEPTED");
    var declined =
        countText(outcomes, "/data/declineManagerInvitation/invitation/status", "DECLINED");
    var misses =
        countText(
                outcomes,
                "/data/acceptManagerInvitation/userErrors/0/__typename",
                "ManagerInvitationNotFoundError")
            + countText(
                outcomes,
                "/data/declineManagerInvitation/userErrors/0/__typename",
                "ManagerInvitationNotFoundError");
    assertThat(accepted + declined).isEqualTo(1);
    assertThat(misses).isEqualTo(1);

    var persisted =
        invitationRepository
            .findById(UUID.fromString(issued.path("invitation").path("id").asString()))
            .orElseThrow();
    assertThat(persisted.getStatus())
        .isIn(ProfileManagerInvitationStatus.ACCEPTED, ProfileManagerInvitationStatus.DECLINED);
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), orphan.getId()))
        .isEqualTo(accepted == 1);
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo((int) accepted);
  }

  @Test
  @DisplayName("Should let exactly one override grant win when two grants race")
  void shouldLetExactlyOneOverrideGrantWinWhenTwoGrantsRace() throws Exception {
    var orphan = managedOrphan();
    var bearer = authTestSupport.freshAccountBearer(owner);
    var request =
        new ConcurrentGraphqlCall(
            bearer, grantOverrideMutation(orphan, recipient.account().getId().toString()));

    var outcomes =
        raceGraphql(new GraphqlRaceLock(RaceLockTarget.PROFILE, orphan.getId()), request, request);

    assertThat(
            countText(
                outcomes, "/data/grantProfileManagerOverride/profileId", orphan.getId().toString()))
        .isEqualTo(1);
    assertThat(
            countText(
                outcomes,
                "/data/grantProfileManagerOverride/userErrors/0/__typename",
                "AlreadyManagerError"))
        .isEqualTo(1);
    assertThat(
            profileManagerRepository.findByProfileId(orphan.getId()).stream()
                .filter(manager -> manager.getAccountId().equals(recipient.account().getId())))
        .hasSize(1);
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT,
                SECURITY_AUDIT_EVENT.OPERATION.eq("grantProfileManagerOverride")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should let exactly one override removal win when two removals race")
  void shouldLetExactlyOneOverrideRemovalWinWhenTwoRemovalsRace() throws Exception {
    var orphan = managedOrphan();
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(recipient.account().getId())
                    .profileId(orphan.getId())
                    .build()));
    var bearer = authTestSupport.freshAccountBearer(owner);
    var request =
        new ConcurrentGraphqlCall(
            bearer,
            """
            mutation { removeProfileManagerOverride(input: {profileId: "%s", accountId: "%s",
              reason: "abuse report"}) { profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId(), recipient.account().getId()));

    var outcomes =
        raceGraphql(new GraphqlRaceLock(RaceLockTarget.PROFILE, orphan.getId()), request, request);

    assertThat(
            countText(
                outcomes,
                "/data/removeProfileManagerOverride/profileId",
                orphan.getId().toString()))
        .isEqualTo(1);
    assertThat(
            countText(
                outcomes,
                "/data/removeProfileManagerOverride/userErrors/0/__typename",
                "NotAManagerError"))
        .isEqualTo(1);
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), orphan.getId()))
        .isFalse();
    assertThat(
            dsl.fetchCount(
                SECURITY_AUDIT_EVENT,
                SECURITY_AUDIT_EVENT.OPERATION.eq("removeProfileManagerOverride")))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should retain an eligible manager when the sole manager relinquishes")
  void shouldHoldHomeAnchorWhenSoleManagerRelinquishes() throws Exception {
    var orphan = managedOrphan();

    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.relinquishProfileManagement.userErrors[0].__typename")
                .value("EligibleManagerRequiredError"));

    // With a second eligible local manager, the same relinquish succeeds.
    transactionTemplate.executeWithoutResult(
        _ ->
            profileManagerRepository.saveAndFlush(
                ProfileManager.builder()
                    .accountId(secondLocalManagerId())
                    .profileId(orphan.getId())
                    .build()));
    graphql(
            authTestSupport.accountBearer(owner),
            """
            mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.relinquishProfileManagement.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.relinquishProfileManagement.profileId")
                .value(orphan.getId().toString()));
  }

  @Test
  @DisplayName(
      "Should require a fresh ServerAdmin and invalidate restorable proposals when an override removes a manager")
  void shouldRequireFreshServerAdminAndInvalidateRestorableProposalsWhenOverrideRemovesManager()
      throws Exception {
    var orphan = managedOrphan();

    graphql(
            authTestSupport.accountBearer(owner),
            grantOverrideMutation(orphan, recipient.account().getId().toString()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.grantProfileManagerOverride.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(owner),
            grantOverrideMutation(orphan, recipient.account().getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.grantProfileManagerOverride.userErrors").isEmpty());
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), orphan.getId()))
        .isTrue();

    // A rival pending invitation (raced in before the grant) must not restore what the
    // removal disputes; the mutation itself refuses to propose an existing manager.
    var restorable =
        invitationRepository.saveAndFlush(
            ProfileManagerInvitation.builder()
                .profileId(orphan.getId())
                .profileName(orphan.getName())
                .inviterAccountId(owner.account().getId())
                .inviterDisplayName(owner.account().getDisplayName())
                .recipientAccountId(recipient.account().getId())
                .recipientEmail(recipient.account().getEmail())
                .expiresAt(Instant.now().plusSeconds(3600))
                .publicId(UUID.randomUUID().toString())
                .secretDigest(new byte[] {1})
                .build());
    var restorableId = restorable.getId().toString();

    graphql(
            authTestSupport.freshAccountBearer(owner),
            """
            mutation { removeProfileManagerOverride(input: {profileId: "%s", accountId: "%s",
              reason: "abuse report"}) { profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId(), recipient.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.removeProfileManagerOverride.userErrors").isEmpty());

    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                recipient.account().getId(), orphan.getId()))
        .isFalse();
    assertThat(
            invitationRepository.findById(UUID.fromString(restorableId)).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo(2);
    assertAuditEvent(
        ExpectedAuditEvent.builder()
            .operation("grantProfileManagerOverride")
            .reason("support")
            .actorAccountId(owner.account().getId())
            .profileId(orphan.getId())
            .accountId(recipient.account().getId())
            .build());
    assertAuditEvent(
        ExpectedAuditEvent.builder()
            .operation("removeProfileManagerOverride")
            .reason("abuse report")
            .actorAccountId(owner.account().getId())
            .profileId(orphan.getId())
            .accountId(recipient.account().getId())
            .build());
  }

  private String grantOverrideMutation(Profile profile, String accountId) {
    return """
           mutation { grantProfileManagerOverride(input: {profileId: "%s", accountId: "%s",
             reason: "support"}) { profileId userErrors { __typename } } }
           """
        .formatted(profile.getId(), accountId);
  }

  private void assertAuditEvent(ExpectedAuditEvent expected) throws Exception {
    var event =
        dsl.selectFrom(SECURITY_AUDIT_EVENT)
            .where(SECURITY_AUDIT_EVENT.OPERATION.eq(expected.operation()))
            .fetchSingle();
    assertThat(event.getActorAccountId()).isEqualTo(expected.actorAccountId());
    assertThat(event.getReason()).isEqualTo(expected.reason());
    assertThat(event.getOccurredAt()).isNotNull();
    var resources = objectMapper.readTree(event.getResources().data());
    assertThat(resources.path("profileId").asString()).isEqualTo(expected.profileId().toString());
    assertThat(resources.path("accountId").asString()).isEqualTo(expected.accountId().toString());
  }

  @Builder
  private record ExpectedAuditEvent(
      String operation, String reason, UUID actorAccountId, UUID profileId, UUID accountId) {}

  private void persistPendingInvitations(int count) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          for (var index = 0; index < count; index++) {
            var profile =
                profileRepository.save(
                    ProfileFixture.defaultProfileBuilder()
                        .householdId(owner.household().getId())
                        .name("Managed orphan " + index)
                        .build());
            profileManagerRepository.save(
                ProfileManager.builder()
                    .accountId(owner.account().getId())
                    .profileId(profile.getId())
                    .build());
            shareRepository.save(
                ProfileHouseholdShare.builder()
                    .profileId(profile.getId())
                    .householdId(owner.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .build());
            invitationRepository.save(
                ProfileManagerInvitation.builder()
                    .profileId(profile.getId())
                    .profileName(profile.getName())
                    .inviterAccountId(owner.account().getId())
                    .inviterDisplayName(owner.account().getDisplayName())
                    .recipientAccountId(recipient.account().getId())
                    .recipientEmail(recipient.account().getEmail())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .publicId(UUID.randomUUID().toString())
                    .secretDigest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                    .build());
          }
        });
  }

  private List<JsonNode> raceGraphql(
      GraphqlRaceLock raceLock, ConcurrentGraphqlCall first, ConcurrentGraphqlCall second)
      throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    List<Future<JsonNode>> attempts;
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      attempts =
          List.of(first, second).stream()
              .map(
                  call ->
                      executor.submit(
                          () -> {
                            ready.countDown();
                            assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                            var response =
                                graphql(call.bearer(), call.query())
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.errors").doesNotExist())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString();
                            return objectMapper.readTree(response);
                          }))
              .toList();
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      transactionTemplate.executeWithoutResult(
          _ -> {
            acquireRaceLock(raceLock);
            start.countDown();
            Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(blockedRaceRequestCount(raceLock)).isEqualTo(2));
          });
      return List.of(
          attempts.getFirst().get(30, TimeUnit.SECONDS),
          attempts.getLast().get(30, TimeUnit.SECONDS));
    }
  }

  private long countText(List<JsonNode> nodes, String pointer, String expected) {
    return nodes.stream().filter(node -> expected.equals(node.at(pointer).asString())).count();
  }

  private UUID persistedInvitationId(JsonNode issued) {
    return UUID.fromString(issued.path("invitation").path("id").asString());
  }

  private void acquireRaceLock(GraphqlRaceLock raceLock) {
    switch (raceLock.target()) {
      case PROFILE ->
          assertThat(profileRepository.lockPolicyById(raceLock.id()))
              .as("race Profile")
              .isPresent();
      case INVITATION ->
          assertThat(
                  dsl.selectOne()
                      .from(PROFILE_MANAGER_INVITATION)
                      .where(PROFILE_MANAGER_INVITATION.ID.eq(raceLock.id()))
                      .forUpdate()
                      .fetchOptional())
              .as("race invitation")
              .isPresent();
    }
  }

  private int blockedRaceRequestCount(GraphqlRaceLock raceLock) {
    var query = DSL.field("query", String.class);
    var targetQuery =
        switch (raceLock.target()) {
          case PROFILE ->
              query.containsIgnoreCase("profile").and(query.containsIgnoreCase("for update"));
          case INVITATION ->
              query
                  .startsWithIgnoreCase("update")
                  .and(query.containsIgnoreCase("profile_manager_invitation"));
        };
    return dsl.fetchCount(
        dsl.selectOne()
            .from("pg_stat_activity")
            .where(DSL.field("wait_event_type", String.class).eq("Lock"))
            .and(targetQuery));
  }

  private record ConcurrentGraphqlCall(String bearer, String query) {}

  private record GraphqlRaceLock(RaceLockTarget target, UUID id) {}

  private enum RaceLockTarget {
    PROFILE,
    INVITATION
  }

  /** A second eligible MEMBER of the owner's Household, with its own anchored Personal Profile. */
  private UUID secondLocalManagerId() {
    var personal =
        profileRepository.saveAndFlush(
            ProfileFixture.defaultProfileBuilder()
                .householdId(owner.household().getId())
                .name("Second Manager")
                .build());
    var account =
        userAccountRepository.saveAndFlush(
            AccountFixture.defaultAccountBuilder()
                .householdId(owner.household().getId())
                .householdRole(HouseholdRole.MEMBER)
                .personalProfileId(personal.getId())
                .build());
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(personal.getId())
            .householdId(owner.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .structural(true)
            .build());
    return account.getId();
  }

  /** An unlinked Profile the owner manages, available at home. */
  private Profile managedOrphan() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(owner.household().getId())
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
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }
}
