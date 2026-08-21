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
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
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
import tools.jackson.databind.JsonNode;
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
  @Autowired private EntityManagerFactory entityManagerFactory;
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
  @DisplayName("Should revoke server admin and audit the win when a fresh ServerAdmin asks")
  void shouldRevokeServerAdminAndAuditWinWhenFreshServerAdminAsks() throws Exception {
    assertThat(userAccountRepository.tryGrantServerAdmin(resident.account().getId())).isTrue();

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { revokeServerAdmin(input: {accountId: "%s", reason: "rotation"}) {
              account { id serverAdmin } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.revokeServerAdmin.account.serverAdmin").value(false))
        .andExpect(jsonPath("$.data.revokeServerAdmin.userErrors").isEmpty());

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isFalse();
    assertThat(dsl.selectFrom(SECURITY_AUDIT_EVENT).fetch())
        .singleElement()
        .satisfies(
            audit -> {
              assertThat(audit.getOperation()).isEqualTo("revokeServerAdmin");
              assertThat(audit.getReason()).isEqualTo("rotation");
            });
  }

  @Test
  @DisplayName("Should report the missing ceremony when revoking with a stale token")
  void shouldReportMissingCeremonyWhenRevokingServerAdminWithStaleToken() throws Exception {
    assertThat(userAccountRepository.tryGrantServerAdmin(resident.account().getId())).isTrue();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { revokeServerAdmin(input: {accountId: "%s", reason: "rotation"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.revokeServerAdmin.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should require a reason before revoking server admin")
  void shouldRequireReasonBeforeRevokingServerAdmin() throws Exception {
    assertThat(userAccountRepository.tryGrantServerAdmin(resident.account().getId())).isTrue();

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { revokeServerAdmin(input: {accountId: "%s", reason: "  "}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.revokeServerAdmin.userErrors[0].__typename")
                .value("ReasonRequiredError"));

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
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
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
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

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .isServerAdmin())
        .isFalse();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should read a hidden Account as not found under the oracle rule")
  void shouldReadHiddenAccountAsNotFoundUnderOracleRule() throws Exception {
    var hidden = authTestSupport.createIdentity();
    try {
      graphql(
              authTestSupport.freshAccountBearer(resident),
              """
              mutation { grantServerAdmin(input: {accountId: "%s", reason: "power grab"}) {
                account { id } userErrors { __typename } } }
              """
                  .formatted(hidden.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(
              jsonPath("$.data.grantServerAdmin.userErrors[0].__typename")
                  .value("AccountNotFoundError"));

      assertThat(
              userAccountRepository
                  .findById(hidden.account().getId())
                  .orElseThrow()
                  .isServerAdmin())
          .isFalse();
      assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
    } finally {
      authTestSupport.deleteIdentity(hidden);
    }
  }

  @Test
  @DisplayName("Should return an input error when an administration mutation ID is malformed")
  void shouldReturnInputErrorWhenAdministrationMutationIdIsMalformed() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { grantServerAdmin(input: {accountId: "not-a-uuid", reason: "new operator"}) {
              account { id }
              userErrors { __typename ... on InputMutationError { message inputPath } }
            } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.grantServerAdmin.account").doesNotExist())
        .andExpect(
            jsonPath("$.data.grantServerAdmin.userErrors[0].__typename").value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.grantServerAdmin.userErrors[0].inputPath[0]").value("accountId"));
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

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .getHouseholdRole())
        .isEqualTo(HouseholdRole.ADMIN);
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should grant HouseholdAdmin and audit the winning transition")
  void shouldGrantHouseholdAdminAndAuditWinningTransition() throws Exception {
    var member = joinHousehold(resident, HouseholdRole.MEMBER);

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { grantHouseholdAdmin(input: {accountId: "%s"}) {
              account { householdRole } userErrors { __typename } } }
            """
                .formatted(member.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.grantHouseholdAdmin.account.householdRole").value("ADMIN"))
        .andExpect(jsonPath("$.data.grantHouseholdAdmin.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(member.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.ADMIN);
    assertThat(dsl.selectFrom(SECURITY_AUDIT_EVENT).fetch())
        .singleElement()
        .extracting(audit -> audit.getOperation())
        .isEqualTo("grantHouseholdAdmin");
  }

  @Test
  @DisplayName("Should revoke HouseholdAdmin and audit the winning transition")
  void shouldRevokeHouseholdAdminAndAuditWinningTransition() throws Exception {
    var additionalAdmin = joinHousehold(resident, HouseholdRole.ADMIN);

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { revokeHouseholdAdmin(input: {accountId: "%s"}) {
              account { householdRole } userErrors { __typename } } }
            """
                .formatted(additionalAdmin.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.revokeHouseholdAdmin.account.householdRole").value("MEMBER"))
        .andExpect(jsonPath("$.data.revokeHouseholdAdmin.userErrors").isEmpty());

    assertThat(
            userAccountRepository
                .findById(additionalAdmin.getId())
                .orElseThrow()
                .getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);
    assertThat(dsl.selectFrom(SECURITY_AUDIT_EVENT).fetch())
        .singleElement()
        .extracting(audit -> audit.getOperation())
        .isEqualTo("revokeHouseholdAdmin");
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

    assertThat(userAccountRepository.findById(member.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
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
  @DisplayName("Should enable an Account and audit the winning transition")
  void shouldEnableAccountAndAuditWinningTransition() throws Exception {
    assertThat(userAccountRepository.tryDisable(resident.account().getId())).isTrue();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { enableAccount(input: {accountId: "%s"}) {
              account { enabled } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.enableAccount.account.enabled").value(true))
        .andExpect(jsonPath("$.data.enableAccount.userErrors").isEmpty());

    assertThat(userAccountRepository.findById(resident.account().getId()).orElseThrow().isEnabled())
        .isTrue();
    assertThat(dsl.selectFrom(SECURITY_AUDIT_EVENT).fetch())
        .singleElement()
        .extracting(audit -> audit.getOperation())
        .isEqualTo("enableAccount");
  }

  @Test
  @DisplayName("Should let an Account rename itself")
  void shouldLetAccountRenameItself() throws Exception {
    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameAccount(input: {accountId: "%s", displayName: "Fresh Name"}) {
              account { displayName } userErrors { __typename } } }
            """
                .formatted(resident.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.renameAccount.account.displayName").value("Fresh Name"));

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .getDisplayName())
        .isEqualTo("Fresh Name");
  }

  @Test
  @DisplayName("Should refuse a blank Account display name without changing it")
  void shouldRefuseBlankAccountDisplayNameWithoutChangingIt() throws Exception {
    var originalName = resident.account().getDisplayName();

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

    assertThat(
            userAccountRepository
                .findById(resident.account().getId())
                .orElseThrow()
                .getDisplayName())
        .isEqualTo(originalName);
  }

  @Test
  @DisplayName("Should let a HouseholdAdmin rename its Household")
  void shouldLetHouseholdAdminRenameItsHousehold() throws Exception {
    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { renameHousehold(input: {householdId: "%s", name: "Summer House"}) {
              household { name } userErrors { __typename } } }
            """
                .formatted(resident.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.renameHousehold.household.name").value("Summer House"));

    assertThat(householdRepository.findById(resident.household().getId()).orElseThrow().getName())
        .isEqualTo("Summer House");
  }

  @Test
  @DisplayName("Should hide a foreign Household when rename is requested")
  void shouldHideForeignHouseholdWhenRenameRequested() throws Exception {
    var originalName = serverAdmin.household().getName();

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

    assertThat(
            householdRepository.findById(serverAdmin.household().getId()).orElseThrow().getName())
        .isEqualTo(originalName);
  }

  @Test
  @DisplayName("Should create a Household for ServerAdmin")
  void shouldCreateHouseholdForServerAdmin() throws Exception {
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
    assertThat(householdRepository.findById(householdId).orElseThrow().getName())
        .isEqualTo("Guest House");
    householdRepository.deleteById(householdId);
  }

  @Test
  @DisplayName("Should forbid Household creation for a non-ServerAdmin")
  void shouldForbidHouseholdCreationForNonServerAdmin() throws Exception {
    var householdCount = householdRepository.count();

    graphql(
            authTestSupport.accountBearer(resident),
            """
            mutation { createHousehold(input: {name: "Not Allowed"}) {
              household { id } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    assertThat(householdRepository.count()).isEqualTo(householdCount);
  }

  @Test
  @DisplayName("Should list every Household with its Accounts for ServerAdmin only")
  void shouldListEveryHouseholdWithItsAccountsForServerAdminOnly() throws Exception {
    var firstPage = householdPage(null);
    var after =
        firstPage.path("data").path("households").path("pageInfo").path("endCursor").asString();
    var secondPage = householdPage(after);

    var firstNode = firstPage.path("data").path("households").path("edges").get(0).path("node");
    var secondNode = secondPage.path("data").path("households").path("edges").get(0).path("node");
    assertThat(List.of(firstNode.path("id").asString(), secondNode.path("id").asString()))
        .containsExactlyInAnyOrder(
            serverAdmin.household().getId().toString(), resident.household().getId().toString());
    assertThat(
            firstNode.path("accounts").path("edges").get(0).path("node").path("email").asString())
        .isIn(serverAdmin.account().getEmail(), resident.account().getEmail());
    assertThat(
            secondNode.path("accounts").path("edges").get(0).path("node").path("email").asString())
        .isIn(serverAdmin.account().getEmail(), resident.account().getEmail())
        .isNotEqualTo(
            firstNode.path("accounts").path("edges").get(0).path("node").path("email").asString());

    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { households(first: 50) { edges { node { id } } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should continue Household pagination when the cursor Household is renamed")
  void shouldContinueHouseholdPaginationWhenCursorHouseholdIsRenamed() throws Exception {
    var firstPage = householdPage(null);
    var firstNode = firstPage.path("data").path("households").path("edges").get(0).path("node");
    var after =
        firstPage.path("data").path("households").path("pageInfo").path("endCursor").asString();

    householdRepository.tryRename(UUID.fromString(firstNode.path("id").asString()), "zzzzzzzzzz");

    var secondPage = householdPage(after);
    var ids =
        List.of(
            firstNode.path("id").asString(),
            secondPage
                .path("data")
                .path("households")
                .path("edges")
                .get(0)
                .path("node")
                .path("id")
                .asString());
    assertThat(ids)
        .containsExactlyInAnyOrder(
            serverAdmin.household().getId().toString(), resident.household().getId().toString());
  }

  @Test
  @DisplayName("Should batch nested Account pages across the Household page")
  void shouldBatchNestedAccountPagesAcrossHouseholdPage() throws Exception {
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              query { households(first: 10) { edges { node { id
                accounts(first: 10) { edges { node { id email } } } } } } }
              """)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist());

      assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    } finally {
      statistics.setStatisticsEnabled(false);
    }
  }

  @Test
  @DisplayName("Should show a Household to its admin and hide a foreign one as null")
  void shouldShowHouseholdToItsAdminAndHideForeignOneAsNull() throws Exception {
    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { householdAdministration(householdId: "%s") { id name
              accounts(first: 10) { edges { node { email } } } } }
            """
                .formatted(resident.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.householdAdministration.id")
                .value(resident.household().getId().toString()))
        .andExpect(
            jsonPath("$.data.householdAdministration.accounts.edges[0].node.email")
                .value(resident.account().getEmail()));

    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { householdAdministration(householdId: "%s") { id } }
            """
                .formatted(serverAdmin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.householdAdministration").doesNotExist());
  }

  @Test
  @DisplayName("Should show Account administration to its Household admin and hide it otherwise")
  void shouldShowAccountAdministrationToItsHouseholdAdminAndHideItOtherwise() throws Exception {
    var member = joinHousehold(resident, HouseholdRole.MEMBER);

    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { accountAdministration(accountId: "%s") { id email enabled householdRole } }
            """
                .formatted(member.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.accountAdministration.householdRole").value("MEMBER"));

    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { accountAdministration(accountId: "%s") { id } }
            """
                .formatted(serverAdmin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.accountAdministration").doesNotExist());
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  private JsonNode householdPage(String after) throws Exception {
    var afterArgument = after == null ? "" : ", after: \"" + after + "\"";
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                query { households(first: 1%s) { pageInfo { endCursor hasNextPage }
                  edges { node { id name accounts(first: 10) {
                    edges { node { id email householdRole } } } } } } }
                """
                    .formatted(afterArgument))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
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
