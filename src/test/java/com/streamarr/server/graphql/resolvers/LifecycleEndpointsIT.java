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
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
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
 * Transfers and deletion through the GraphQL boundary against real PostgreSQL and Cedar: the
 * Account moves with its Personal Profile, T1/T8 judge the move at commit as typed rejections, a
 * partial transfer write never disturbs credentials, and the deletion paths leave nothing behind.
 */
@Tag("IntegrationTest")
@DisplayName("Lifecycle Endpoints Integration Tests")
class LifecycleEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createAdminIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should move the Account with its Profile while credentials survive untouched")
  void shouldMoveAccountWithItsProfileWhileCredentialsSurviveUntouched() throws Exception {
    var mover = residentOf(admin.household().getId(), "Mover", HouseholdRole.MEMBER);

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferAccount(input: {accountId: "%s",
              destinationHouseholdId: "%s", sourceAccess: END, reason: "support"}) {
              account { householdId householdRole } userErrors { __typename } } }
            """
                .formatted(mover.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.transferAccount.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.transferAccount.account.householdId")
                .value(host.household().getId().toString()))
        .andExpect(jsonPath("$.data.transferAccount.account.householdRole").value("MEMBER"));

    var moved = userAccountRepository.findById(mover.getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(host.household().getId());
    assertThat(
            profileRepository.findById(moved.getPersonalProfileId()).orElseThrow().getHouseholdId())
        .isEqualTo(host.household().getId());
    // The partial transfer write left the password hash and display name alone.
    assertThat(moved.getPasswordHash()).isEqualTo(mover.getPasswordHash());
    assertThat(moved.getDisplayName()).isEqualTo("Mover");
    var structural =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                moved.getPersonalProfileId(), host.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();
    assertThat(structural.isStructural()).isTrue();
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                moved.getPersonalProfileId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();
  }

  @Test
  @DisplayName("Should answer the commit-time judgments as typed rejections")
  void shouldAnswerCommitTimeJudgmentsAsTypedRejections() throws Exception {
    // T1: the source cannot lose its only HouseholdAdmin.
    residentOf(admin.household().getId(), "Stays", HouseholdRole.MEMBER);
    graphql(
            authTestSupport.accountBearer(admin),
            transferMutation(admin.account().getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferAccount.userErrors[0].__typename")
                .value("LastHouseholdAdminError"));

    // T8: the destination already shows that name.
    var mover = residentOf(admin.household().getId(), "Mover", HouseholdRole.MEMBER);
    var twinName = profileRepository.findById(mover.getPersonalProfileId()).orElseThrow().getName();
    transactionTemplate.executeWithoutResult(
        _ -> {
          var twin =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(host.household().getId())
                      .name(twinName)
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(host.account().getId())
                  .profileId(twin.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(twin.getId())
                  .householdId(host.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
        });
    graphql(
            authTestSupport.accountBearer(admin),
            transferMutation(mover.getId(), host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.transferAccount.userErrors[0].__typename")
                .value("ProfileNameTakenError"));
  }

  @Test
  @DisplayName("Should erase an Account and keep one only behind a replacement anchor")
  void shouldEraseAccountAndKeepOneOnlyBehindReplacementAnchor() throws Exception {
    var doomed = residentOf(admin.household().getId(), "Doomed", HouseholdRole.MEMBER);

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              reason: "leaving"}) { accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteAccount.userErrors[0].__typename")
                .value("ReplacementManagerRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { deleteAccount(input: {accountId: "%s", profileDisposition: KEEP,
              replacementManagerAccountId: "%s", reason: "leaving"}) {
              accountId userErrors { __typename } } }
            """
                .formatted(doomed.getId(), admin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(doomed.getId())).isEmpty();
    var preserved = profileRepository.findById(doomed.getPersonalProfileId()).orElseThrow();
    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                admin.account().getId(), preserved.getId()))
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isEqualTo(1);
  }

  @Test
  @DisplayName("Should let a fresh person delete their own Account after typing DELETE")
  void shouldLetFreshPersonDeleteTheirOwnAccountAfterTypingDelete() throws Exception {
    var buddyId = residentOf(host.household().getId(), "Buddy", HouseholdRole.ADMIN).getId();
    assertThat(buddyId).isNotNull();

    graphql(
            authTestSupport.freshAccountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "delete"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteMyAccount.userErrors[0].__typename")
                .value("ConfirmationRequiredError"));

    graphql(
            authTestSupport.accountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "DELETE"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.deleteMyAccount.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    graphql(
            authTestSupport.freshAccountBearer(host),
            """
            mutation { deleteMyAccount(input: {confirmation: "DELETE"}) {
              accountId userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.deleteMyAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(host.account().getId())).isEmpty();
  }

  @Test
  @DisplayName("Should move an unlinked Profile behind its new anchor and force-delete freshly")
  void shouldMoveUnlinkedProfileBehindNewAnchorAndForceDeleteFreshly() throws Exception {
    var orphan = managedOrphan();

    graphql(
            authTestSupport.accountBearer(admin),
            """
            mutation { transferProfile(input: {profileId: "%s",
              destinationHouseholdId: "%s", localManagerAccountId: "%s"}) {
              profile { householdId } userErrors { __typename } } }
            """
                .formatted(orphan.getId(), host.household().getId(), host.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.transferProfile.userErrors").isEmpty())
        .andExpect(
            jsonPath("$.data.transferProfile.profile.householdId")
                .value(host.household().getId().toString()));

    assertThat(
            profileManagerRepository.existsByAccountIdAndProfileId(
                host.account().getId(), orphan.getId()))
        .isTrue();
    assertThat(
            shareRepository.findByProfileIdAndHouseholdIdAndStatus(
                orphan.getId(), admin.household().getId(), ProfileShareStatus.ACTIVE))
        .isEmpty();

    graphql(
            authTestSupport.freshAccountBearer(admin),
            """
            mutation { forceDeleteProfile(input: {profileId: "%s", reason: "abuse report"}) {
              profileId userErrors { __typename } } }
            """
                .formatted(orphan.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.forceDeleteProfile.userErrors").isEmpty());
    assertThat(profileRepository.findById(orphan.getId())).isEmpty();
  }

  private String transferMutation(UUID accountId, UUID destinationHouseholdId) {
    return """
           mutation { transferAccount(input: {accountId: "%s",
             destinationHouseholdId: "%s", sourceAccess: END}) {
             account { id } userErrors { __typename } } }
           """
        .formatted(accountId, destinationHouseholdId);
  }

  /** A complete resident of the Household: Account, anchored Personal Profile, structural share. */
  private UserAccount residentOf(UUID householdId, String displayName, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var personal =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(householdId)
                      .name(displayName)
                      .build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(role)
                      .displayName(displayName)
                      .personalProfileId(personal.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(personal.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private Profile managedOrphan() {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(admin.household().getId())
                      .name("Orphan")
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
