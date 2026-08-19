package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
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
 * Household and Account administration through the GraphQL boundary against real PostgreSQL and the
 * real Cedar engine: the authority table's allow and deny rows, the oracle rule (visible →
 * FORBIDDEN, hidden → not-found), the fresh-reauthentication classification, and the deferred
 * invariants T1/T4/T5 translated into typed user errors after rollback.
 */
@Tag("IntegrationTest")
@DisplayName("Administration Endpoints Integration Tests")
class AdministrationEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthTestSupport.TestIdentity resident;

  @BeforeEach
  void setUp() {
    serverAdmin = authTestSupport.createAdminIdentity();
    resident = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(resident);
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should grant server admin and audit the win when a fresh ServerAdmin asks")
  void shouldGrantServerAdminAndAuditWinWhenFreshServerAdminAsks() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { grantServerAdmin(input: {accountId: "%s", reason: "new operator"}) {
              account { id serverAdmin } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.grantServerAdmin.account.serverAdmin").value(true))
        .andExpect(jsonPath("$.data.grantServerAdmin.userErrors").isEmpty());

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isTrue();
    var audit =
        dsl.selectFrom(SECURITY_AUDIT_EVENT)
            .where(SECURITY_AUDIT_EVENT.OPERATION.eq("grantServerAdmin"))
            .fetch();
    assertThat(audit).hasSize(1);
    assertThat(audit.getFirst().getActorAccountId()).isEqualTo(serverAdmin.account().getId());
    assertThat(audit.getFirst().getReason()).isEqualTo("new operator");
    assertThat(audit.getFirst().getResources().data())
        .contains(resident.account().getId().toString());
  }

  @Test
  @DisplayName("Should report the missing ceremony when the ServerAdmin token is not fresh")
  void shouldReportMissingCeremonyWhenServerAdminTokenNotFresh() throws Exception {
    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { grantServerAdmin(input: {accountId: "%s", reason: "new operator"}) {
              account { id } userErrors { __typename ... on MutationError { message } } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.grantServerAdmin.account").doesNotExist())
        .andExpect(
            jsonPath("$.data.grantServerAdmin.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isFalse();
  }

  @Test
  @DisplayName("Should forbid a HouseholdAdmin who can see the Account but lacks authority")
  void shouldForbidHouseholdAdminWhoCanSeeAccountButLacksAuthority() throws Exception {
    var peer = joinHousehold(resident, HouseholdRole.ADMIN);
    var peerIdentity = identityFor(peer);

    graphql(
            authTestSupport.freshAccountBearer(peerIdentity),
            """
            mutation { grantServerAdmin(input: {accountId: "%s", reason: "power grab"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should read a hidden Account as not found under the oracle rule")
  void shouldReadHiddenAccountAsNotFoundUnderOracleRule() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(resident),
            """
            mutation { grantServerAdmin(input: {accountId: "%s", reason: "power grab"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(serverAdmin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.grantServerAdmin.userErrors[0].__typename")
                .value("AccountNotFoundError"));
  }

  @Test
  @DisplayName("Should translate revoking the last enabled ServerAdmin into a typed error")
  void shouldTranslateRevokingLastEnabledServerAdminIntoTypedError() throws Exception {
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, serverAdmin.account().getId())
        .execute();

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { revokeServerAdmin(input: {accountId: "%s", reason: "stepping down"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(serverAdmin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.revokeServerAdmin.userErrors[0].__typename")
                .value("LastServerAdminError"));

    assertThat(
            userAccountRepository
                .findById(serverAdmin.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should translate demoting the last HouseholdAdmin into a typed error")
  void shouldTranslateDemotingLastHouseholdAdminIntoTypedError() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { revokeHouseholdAdmin(input: {accountId: "%s"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.revokeHouseholdAdmin.userErrors[0].__typename")
                .value("LastHouseholdAdminError"));
  }

  @Test
  @DisplayName("Should translate promoting a restricted Account into a typed error")
  void shouldTranslatePromotingRestrictedAccountIntoTypedError() throws Exception {
    var member = joinHousehold(resident, HouseholdRole.MEMBER);
    restrictUnderSupervision(resident, member);

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { grantHouseholdAdmin(input: {accountId: "%s"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(member.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.grantHouseholdAdmin.userErrors[0].__typename")
                .value("RestrictedAccountAuthorityError"));
  }

  @Test
  @DisplayName("Should revoke refresh authority when an Account is disabled")
  void shouldRevokeRefreshAuthorityWhenAccountIsDisabled() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { disableAccount(input: {accountId: "%s"}) {
              account { enabled } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disableAccount.account.enabled").value(false));

    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\": \"%s\"}".formatted(resident.rawRefreshToken())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should let an Account rename itself and refuse a blank name")
  void shouldLetAccountRenameItselfAndRefuseBlankName() throws Exception {
    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameAccount(input: {accountId: "%s", displayName: "Fresh Name"}) {
              account { displayName } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.renameAccount.account.displayName").value("Fresh Name"));

    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameAccount(input: {accountId: "%s", displayName: "  "}) {
              account { displayName } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.renameAccount.userErrors[0].__typename")
                .value("DisplayNameRequiredError"));
  }

  @Test
  @DisplayName("Should let a HouseholdAdmin rename its Household and hide a foreign one")
  void shouldLetHouseholdAdminRenameItsHouseholdAndHideForeignOne() throws Exception {
    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameHousehold(input: {householdId: "%s", name: "Summer House"}) {
              household { name } userErrors { __typename } } }
            """
                .formatted(resident.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.renameHousehold.household.name").value("Summer House"));

    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameHousehold(input: {householdId: "%s", name: "Not Yours"}) {
              household { name } userErrors { __typename } } }
            """
                .formatted(serverAdmin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.renameHousehold.userErrors[0].__typename")
                .value("HouseholdNotFoundError"));
  }

  @Test
  @DisplayName("Should create a Household for ServerAdmin and forbid everyone else")
  void shouldCreateHouseholdForServerAdminAndForbidEveryoneElse() throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { createHousehold(input: {name: "Guest House"}) {
                  household { id name } userErrors { __typename } } }
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createHousehold.household.name").value("Guest House"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var householdId =
        UUID.fromString(
            objectMapper
                .readTree(response)
                .path("data")
                .path("createHousehold")
                .path("household")
                .path("id")
                .asString());
    householdRepository.deleteById(householdId);

    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { createHousehold(input: {name: "Not Allowed"}) {
              household { id } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  /** A second Account joining the identity's Household (deleted with the Household). */
  private UserAccount joinHousehold(AuthTestSupport.TestIdentity into, HouseholdRole role) {
    return transactionTemplate.execute(
        _ -> {
          var household = into.household();
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(household.getId()).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(household.getId())
                      .householdRole(role)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(household.getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  /** Restricts the member's Personal Profile with the admin of its Household as manager (T6). */
  private void restrictUnderSupervision(AuthTestSupport.TestIdentity admin, UserAccount member) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(admin.account().getId())
                  .profileId(member.getPersonalProfileId())
                  .build());
          var profile = profileRepository.findById(member.getPersonalProfileId()).orElseThrow();
          profile.setMaximumAllowedRatingAge(12);
          profileRepository.saveAndFlush(profile);
        });
  }

  /** A token-mintable identity for an Account created outside AuthTestSupport. */
  private AuthTestSupport.TestIdentity identityFor(UserAccount account) {
    var household = householdRepository.findById(account.getHouseholdId()).orElseThrow();
    var profile = profileRepository.findById(account.getPersonalProfileId()).orElseThrow();
    return AuthTestSupport.TestIdentity.builder()
        .account(account)
        .household(household)
        .profile(profile)
        .session(sessionFor(account))
        .build();
  }

  private AuthSession sessionFor(UserAccount account) {
    return authSessionRepository.saveAndFlush(
        AuthSession.builder()
            .accountId(account.getId())
            .deviceName("administration-endpoints-it")
            .build());
  }
}
