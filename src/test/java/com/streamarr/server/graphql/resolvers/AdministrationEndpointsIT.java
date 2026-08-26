package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
  @DisplayName("Should require a reason when revoking server admin")
  void shouldRequireReasonWhenRevokingServerAdmin() throws Exception {
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
  @DisplayName(
      "Should forbid a HouseholdAdmin when the Account is visible but authority is missing")
  void shouldForbidHouseholdAdminWhenAccountVisibleButAuthorityMissing() throws Exception {
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
  @DisplayName("Should return Account not found when the oracle rule hides it")
  void shouldReturnAccountNotFoundWhenAccountHiddenByOracleRule() throws Exception {
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

  @ParameterizedTest(name = "Should return an input error when {0} receives a malformed ID")
  @MethodSource("malformedAdministrationIds")
  @DisplayName("Should return an input error when an administration mutation ID is malformed")
  void shouldReturnInputErrorWhenAdministrationMutationIdIsMalformed(
      String operation, String resource, String inputPath, String mutation) throws Exception {
    graphql(authTestSupport.freshAccountBearer(serverAdmin), mutation)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.%s.%s".formatted(operation, resource)).doesNotExist())
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].__typename".formatted(operation))
                .value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.%s.userErrors[0].inputPath[0]".formatted(operation)).value(inputPath));
  }

  @Test
  @DisplayName("Should return a typed error when revoking the last enabled ServerAdmin")
  void shouldReturnTypedErrorWhenRevokingLastEnabledServerAdmin() throws Exception {
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
  @DisplayName("Should translate disabling the Account when it is the last enabled ServerAdmin")
  void shouldTranslateDisablingAccountWhenLastEnabledServerAdmin() throws Exception {
    dsl.insertInto(SERVER_BOOTSTRAP)
        .set(SERVER_BOOTSTRAP.ADMIN_ACCOUNT_ID, serverAdmin.account().getId())
        .execute();

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { disableAccount(input: {accountId: "%s"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(serverAdmin.account().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.disableAccount.userErrors[0].__typename")
                .value("LastServerAdminError"));

    assertThat(
            userAccountRepository.findById(serverAdmin.account().getId()).orElseThrow().isEnabled())
        .isTrue();
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should return a typed error when demoting the last HouseholdAdmin")
  void shouldReturnTypedErrorWhenDemotingLastHouseholdAdmin() throws Exception {
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
  @DisplayName("Should grant HouseholdAdmin and audit when the transition wins")
  void shouldGrantHouseholdAdminAndAuditWhenTransitionWins() throws Exception {
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
  @DisplayName("Should revoke HouseholdAdmin and audit when the transition wins")
  void shouldRevokeHouseholdAdminAndAuditWhenTransitionWins() throws Exception {
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
  @DisplayName("Should return a typed error when promoting a restricted Account")
  void shouldReturnTypedErrorWhenPromotingRestrictedAccount() throws Exception {
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
                .value("RestrictedAccountCannotAdministerError"));

    assertThat(userAccountRepository.findById(member.getId()).orElseThrow().getHouseholdRole())
        .isEqualTo(HouseholdRole.MEMBER);
    assertThat(dsl.fetchCount(SECURITY_AUDIT_EVENT)).isZero();
  }

  @Test
  @DisplayName("Should translate granting ServerAdmin when the Account is restricted")
  void shouldTranslateGrantingServerAdminWhenAccountIsRestricted() throws Exception {
    var member = joinHousehold(resident, HouseholdRole.MEMBER);
    restrictUnderSupervision(resident, member);

    graphql(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { grantServerAdmin(input: {accountId: "%s", reason: "new operator"}) {
              account { id } userErrors { __typename } } }
            """
                .formatted(member.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.grantServerAdmin.userErrors[0].__typename")
                .value("RestrictedAccountCannotAdministerError"));

    assertThat(userAccountRepository.findById(member.getId()).orElseThrow().isServerAdmin())
        .isFalse();
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
  @DisplayName("Should enable an Account and audit when the transition wins")
  void shouldEnableAccountAndAuditWhenTransitionWins() throws Exception {
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
  @DisplayName("Should rename an Account when the caller is that Account")
  void shouldRenameAccountWhenCallerIsSameAccount() throws Exception {
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
  @DisplayName("Should preserve the Account name when the rename value is blank")
  void shouldPreserveAccountNameWhenRenameValueBlank() throws Exception {
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
  @DisplayName("Should rename a Household when the caller is its HouseholdAdmin")
  void shouldRenameHouseholdWhenCallerIsHouseholdAdmin() throws Exception {
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
  @DisplayName("Should create a Household when the caller is ServerAdmin")
  void shouldCreateHouseholdWhenCallerIsServerAdmin() throws Exception {
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
  @DisplayName("Should forbid Household creation when the caller is not ServerAdmin")
  void shouldForbidHouseholdCreationWhenCallerIsNotServerAdmin() throws Exception {
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
  @DisplayName("Should list every Household and Account when the caller is ServerAdmin")
  void shouldListAllHouseholdsAndAccountsWhenCallerIsServerAdmin() throws Exception {
    var page = householdPage(50, null);
    var nodesById = new HashMap<String, JsonNode>();
    for (var edge : page.path("data").path("households").path("edges")) {
      var node = edge.path("node");
      nodesById.put(node.path("id").asString(), node);
    }

    for (var identity : List.of(serverAdmin, resident)) {
      var node = nodesById.get(identity.household().getId().toString());
      assertThat(node).isNotNull();
      assertThat(node.path("accounts").path("edges").get(0).path("node").path("email").asString())
          .isEqualTo(identity.account().getEmail());
    }

    graphql(
            authTestSupport.accountBearer(resident),
            """
            query { households(first: 50) { edges { node { id } } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("Should use the default Household page when pagination arguments are omitted")
  void shouldUseDefaultHouseholdPageWhenPaginationArgumentsAreOmitted() throws Exception {
    var overflow =
        IntStream.range(0, 99)
            .<Household>mapToObj(
                index -> Household.builder().name("Default page overflow " + index).build())
            .toList();
    householdRepository.saveAllAndFlush(overflow);

    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              query { households { pageInfo { hasNextPage } edges { node { id } } } }
              """)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.households.edges.length()").value(100))
          .andExpect(jsonPath("$.data.households.pageInfo.hasNextPage").value(true));
    } finally {
      householdRepository.deleteAllInBatch(overflow);
    }
  }

  @Test
  @DisplayName("Should use the default reverse Household page when only before is provided")
  void shouldUseDefaultReverseHouseholdPageWhenOnlyBeforeProvided() throws Exception {
    var overflow =
        IntStream.range(0, 99)
            .<Household>mapToObj(
                index -> Household.builder().name("Reverse page overflow " + index).build())
            .toList();
    householdRepository.saveAllAndFlush(overflow);

    try {
      var lastPageResponse =
          graphql(
                  authTestSupport.accountBearer(serverAdmin),
                  """
                  query { households(last: 1) { edges { cursor } } }
                  """)
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.errors").doesNotExist())
              .andReturn()
              .getResponse()
              .getContentAsString();
      var before =
          objectMapper
              .readTree(lastPageResponse)
              .path("data")
              .path("households")
              .path("edges")
              .get(0)
              .path("cursor")
              .asString();

      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              query { households(before: "%s") {
                pageInfo { hasNextPage hasPreviousPage }
                edges { node { id } }
              } }
              """
                  .formatted(before))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.households.edges.length()").value(100))
          .andExpect(jsonPath("$.data.households.pageInfo.hasNextPage").value(true))
          .andExpect(jsonPath("$.data.households.pageInfo.hasPreviousPage").value(false));
    } finally {
      householdRepository.deleteAllInBatch(overflow);
    }
  }

  @Test
  @DisplayName("Should continue Household pagination when the cursor Household is renamed")
  void shouldContinueHouseholdPaginationWhenCursorHouseholdIsRenamed() throws Exception {
    assertThat(householdRepository.tryRename(serverAdmin.household().getId(), "! cursor household"))
        .isTrue();
    assertThat(householdRepository.tryRename(resident.household().getId(), "! cursor successor"))
        .isTrue();

    var firstPage = householdPage(1, null);
    var firstNode = firstPage.path("data").path("households").path("edges").get(0).path("node");
    assertThat(firstNode.path("id").asString())
        .isEqualTo(serverAdmin.household().getId().toString());
    var after =
        firstPage.path("data").path("households").path("pageInfo").path("endCursor").asString();

    householdRepository.tryRename(UUID.fromString(firstNode.path("id").asString()), "zzzzzzzzzz");

    var secondPage = householdPage(1, after);
    assertThat(
            secondPage
                .path("data")
                .path("households")
                .path("edges")
                .get(0)
                .path("node")
                .path("id")
                .asString())
        .isEqualTo(resident.household().getId().toString());
  }

  @Test
  @DisplayName("Should paginate Households backward when last and before are provided")
  void shouldPaginateHouseholdsBackwardWhenLastAndBeforeProvided() throws Exception {
    assertThat(householdRepository.tryRename(serverAdmin.household().getId(), "A Household"))
        .isTrue();
    assertThat(householdRepository.tryRename(resident.household().getId(), "Z Household")).isTrue();
    var ordered = householdPage(2, null);
    var before =
        ordered.path("data").path("households").path("edges").get(1).path("cursor").asString();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            query { households(last: 1, before: "%s") {
              pageInfo { hasNextPage }
              edges { node { id } }
            } }
            """
                .formatted(before))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.households.edges.length()").value(1))
        .andExpect(
            jsonPath("$.data.households.edges[0].node.id")
                .value(serverAdmin.household().getId().toString()))
        .andExpect(jsonPath("$.data.households.pageInfo.hasNextPage").value(true));
  }

  @Test
  @DisplayName("Should continue nested Account pagination when an after cursor is provided")
  void shouldContinueNestedAccountPaginationWhenAfterCursorProvided() throws Exception {
    var middle = joinHousehold(resident, HouseholdRole.MEMBER);
    var last = joinHousehold(resident, HouseholdRole.MEMBER);
    userAccountRepository.tryRename(resident.account().getId(), "A Account");
    userAccountRepository.tryRename(middle.getId(), "M Account");
    userAccountRepository.tryRename(last.getId(), "Z Account");

    var firstPage = accountPage("first: 1");
    var after = firstPage.path("edges").get(0).path("cursor").asString();
    var secondPage = accountPage("first: 1, after: \"" + after + "\"");

    assertThat(firstPage.path("edges").get(0).path("node").path("displayName").asString())
        .isEqualTo("A Account");
    assertThat(firstPage.path("pageInfo").path("hasNextPage").asBoolean()).isTrue();
    assertThat(secondPage.path("edges").get(0).path("node").path("displayName").asString())
        .isEqualTo("M Account");
    assertThat(secondPage.path("pageInfo").path("hasPreviousPage").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should paginate nested Accounts backward when last and before are provided")
  void shouldPaginateNestedAccountsBackwardWhenLastAndBeforeProvided() throws Exception {
    var middle = joinHousehold(resident, HouseholdRole.MEMBER);
    var last = joinHousehold(resident, HouseholdRole.MEMBER);
    userAccountRepository.tryRename(resident.account().getId(), "A Account");
    userAccountRepository.tryRename(middle.getId(), "M Account");
    userAccountRepository.tryRename(last.getId(), "Z Account");
    var ordered = accountPage("first: 3");
    var before = ordered.path("edges").get(2).path("cursor").asString();

    var reversePage = accountPage("last: 1, before: \"" + before + "\"");

    assertThat(reversePage.path("edges").get(0).path("node").path("displayName").asString())
        .isEqualTo("M Account");
    assertThat(reversePage.path("pageInfo").path("hasNextPage").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("Should use the default page size when only a before Household cursor is given")
  void shouldUseDefaultPageSizeWhenOnlyBeforeHouseholdCursorIsGiven() throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                query { households(first: 2) { edges { node { id } } pageInfo { endCursor } } }
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var households = objectMapper.readTree(response).path("data").path("households");
    var expectedId = households.path("edges").path(0).path("node").path("id").asString();
    var before = households.path("pageInfo").path("endCursor").asString();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            query { households(before: "%s") { edges { node { id } } } }
            """
                .formatted(before))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.households.edges.length()").value(1))
        .andExpect(jsonPath("$.data.households.edges[0].node.id").value(expectedId));
  }

  @Test
  @DisplayName("Should show owned and hide foreign Households when the caller is HouseholdAdmin")
  void shouldShowOwnedAndHideForeignHouseholdWhenCallerIsHouseholdAdmin() throws Exception {
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
  @DisplayName("Should show owned and hide foreign Accounts when the caller is HouseholdAdmin")
  void shouldShowOwnedAndHideForeignAccountWhenCallerIsHouseholdAdmin() throws Exception {
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

  static Stream<Arguments> malformedAdministrationIds() {
    return Stream.of(
        malformedAccountId(
            "grantServerAdmin",
            "grantServerAdmin(input: {accountId: \"not-a-uuid\", reason: \"reason\"})"),
        malformedAccountId(
            "revokeServerAdmin",
            "revokeServerAdmin(input: {accountId: \"not-a-uuid\", reason: \"reason\"})"),
        malformedAccountId(
            "grantHouseholdAdmin", "grantHouseholdAdmin(input: {accountId: \"not-a-uuid\"})"),
        malformedAccountId(
            "revokeHouseholdAdmin", "revokeHouseholdAdmin(input: {accountId: \"not-a-uuid\"})"),
        malformedAccountId("disableAccount", "disableAccount(input: {accountId: \"not-a-uuid\"})"),
        malformedAccountId("enableAccount", "enableAccount(input: {accountId: \"not-a-uuid\"})"),
        malformedAccountId(
            "renameAccount",
            "renameAccount(input: {accountId: \"not-a-uuid\", displayName: \"name\"})"),
        Arguments.of(
            "renameHousehold",
            "household",
            "householdId",
            mutation(
                "renameHousehold(input: {householdId: \"not-a-uuid\", name: \"name\"})",
                "household")));
  }

  private static Arguments malformedAccountId(String operation, String invocation) {
    return Arguments.of(operation, "account", "accountId", mutation(invocation, "account"));
  }

  private static String mutation(String invocation, String resource) {
    return """
        mutation { %s {
          %s { id }
          userErrors { __typename ... on InputMutationError { message inputPath } }
        } }
        """
        .formatted(invocation, resource);
  }

  private JsonNode householdPage(int first, String after) throws Exception {
    var afterArgument = after == null ? "" : ", after: \"" + after + "\"";
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                query { households(first: %d%s) { pageInfo { endCursor hasNextPage }
                  edges { cursor node { id name accounts(first: 10) {
                    edges { node { id email householdRole } } } } } } }
                """
                    .formatted(first, afterArgument))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private JsonNode accountPage(String pagination) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(resident),
                """
                query { householdAdministration(householdId: "%s") {
                  accounts(%s) { pageInfo { hasNextPage hasPreviousPage }
                    edges { cursor node { id displayName } } }
                } }
                """
                    .formatted(resident.household().getId(), pagination))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(response)
        .path("data")
        .path("householdAdministration")
        .path("accounts");
  }

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
