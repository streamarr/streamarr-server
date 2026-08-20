package com.streamarr.server.graphql.resolvers;

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
 * The direct-manager lifecycle through the GraphQL boundary against real PostgreSQL and Cedar:
 * invitation and consent (the code appears exactly once), one winner per transition, T6's anchor
 * rule on relinquishing, and the fresh-reauthenticated override killing restorable proposals.
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
  @DisplayName("Should run the invitation loop from proposal through consent to management")
  void shouldRunInvitationLoopFromProposalThroughConsentToManagement() throws Exception {
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
  @DisplayName("Should decline once and let the sovereign remove a direct manager")
  void shouldDeclineOnceAndLetSovereignRemoveDirectManager() throws Exception {
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

    // The sovereign curates its own Personal Profile's managers.
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
  @DisplayName("Should hold the home anchor when the sole manager relinquishes")
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
                .value("ManagerAnchorRequiredError"));

    // With a second eligible local manager anchoring, the same relinquish succeeds.
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
  @DisplayName("Should reserve overrides for a fresh ServerAdmin and kill restorable proposals")
  void shouldReserveOverridesForFreshServerAdminAndKillRestorableProposals() throws Exception {
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
  }

  private String grantOverrideMutation(Profile profile, String accountId) {
    return """
           mutation { grantProfileManagerOverride(input: {profileId: "%s", accountId: "%s",
             reason: "support"}) { profileId userErrors { __typename } } }
           """
        .formatted(profile.getId(), accountId);
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
