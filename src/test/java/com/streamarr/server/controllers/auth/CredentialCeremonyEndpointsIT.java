package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The whole credential loop against real PostgreSQL and Cedar: a ServerAdmin issues through GraphQL
 * (the code appears exactly once), the recipient's REST ceremony looks up, accepts or declines by
 * code alone, and the reset ceremony redeems while disabled — changing the password, revoking
 * refresh authority, creating no session.
 */
@Tag("IntegrationTest")
@DisplayName("Credential Ceremony Endpoints Integration Tests")
class CredentialCeremonyEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;

  @BeforeEach
  void setUp() {
    serverAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    userAccountRepository
        .findByEmailIgnoreCase("invitee@example.com")
        .ifPresent(created -> authTestSupport.deleteAccount(created.getId()));
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should run the invitation loop from issue through accept to login")
  void shouldRunInvitationLoopFromIssueThroughAcceptToLogin() throws Exception {
    var code = issueInvitation("invitee@example.com");

    mockMvc
        .perform(
            post("/api/auth/invitation/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.householdName").value(serverAdmin.household().getName()))
        .andExpect(jsonPath("$.profileName").value("Invitee"));

    mockMvc
        .perform(
            post("/api/auth/invitation/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code": "%s", "displayName": "Invitee", \
                    "password": "a strong passphrase", "cookieMode": false}
                    """
                        .formatted(code)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
        .andExpect(jsonPath("$.scope").value("account"));

    var created = userAccountRepository.findByEmailIgnoreCase("invitee@example.com");
    assertThat(created).isPresent();
    assertThat(created.get().getHouseholdId()).isEqualTo(serverAdmin.household().getId());

    // The consumed code answers exactly like an unknown one.
    mockMvc
        .perform(
            post("/api/auth/invitation/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVALID_CODE"));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "invitee@example.com", "password": "a strong passphrase", \
                    "cookieMode": false}
                    """))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should decline an invitation and leave no Account behind")
  void shouldDeclineInvitationAndLeaveNoAccountBehind() throws Exception {
    var code = issueInvitation("invitee@example.com");

    mockMvc
        .perform(
            post("/api/auth/invitation/decline")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)))
        .andExpect(status().isNoContent());

    assertThat(invitationRepository.findAll().getFirst().getStatus())
        .isEqualTo(AccountInvitationStatus.DECLINED);
    assertThat(userAccountRepository.findByEmailIgnoreCase("invitee@example.com")).isEmpty();
  }

  @Test
  @DisplayName("Should replace the pending invitation when the same email is invited again")
  void shouldReplacePendingInvitationWhenSameEmailInvitedAgain() throws Exception {
    var first = issueInvitation("invitee@example.com");
    var second = issueInvitation("invitee@example.com");

    mockMvc
        .perform(
            post("/api/auth/invitation/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(first)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            post("/api/auth/invitation/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(second)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should redeem a reset while disabled, revoke refresh, and create no session")
  void shouldRedeemResetWhileDisabledRevokeRefreshAndCreateNoSession() throws Exception {
    var locked = authTestSupport.createIdentity();
    try {
      var code = issuePasswordReset(locked.account().getId());
      var account = userAccountRepository.findById(locked.account().getId()).orElseThrow();
      account.setEnabled(false);
      userAccountRepository.saveAndFlush(account);

      mockMvc
          .perform(
              post("/api/auth/password-reset/redeem")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"code": "%s", "newPassword": "a brand new passphrase"}
                      """
                          .formatted(code)))
          .andExpect(status().isNoContent());

      // Refresh authority died with the reset; the Account stays disabled for login.
      mockMvc
          .perform(
              post("/api/auth/refresh")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"refreshToken\": \"%s\"}".formatted(locked.rawRefreshToken())))
          .andExpect(status().isUnauthorized());
      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"email": "%s", "password": "a brand new passphrase", "cookieMode": false}
                      """
                          .formatted(locked.account().getEmail())))
          .andExpect(status().isUnauthorized());

      // Re-enabled, the new password signs in.
      var again = userAccountRepository.findById(locked.account().getId()).orElseThrow();
      again.setEnabled(true);
      userAccountRepository.saveAndFlush(again);
      mockMvc
          .perform(
              post("/api/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"email": "%s", "password": "a brand new passphrase", "cookieMode": false}
                      """
                          .formatted(locked.account().getEmail())))
          .andExpect(status().isOk());
    } finally {
      authTestSupport.deleteIdentity(locked);
    }
  }

  @Test
  @DisplayName("Should require the ceremony to issue a reset and audit the winner")
  void shouldRequireCeremonyToIssueResetAndAuditWinner() throws Exception {
    var target = authTestSupport.createIdentity();
    try {
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              mutation { issuePasswordReset(input: {accountId: "%s", reason: "locked out"}) {
                issued { code } userErrors { __typename } } }
              """
                  .formatted(target.account().getId()))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.issuePasswordReset.userErrors[0].__typename")
                  .value("ReauthenticationRequiredError"));

      graphql(
              authTestSupport.freshAccountBearer(serverAdmin),
              """
              mutation { issuePasswordReset(input: {accountId: "%s", reason: "locked out"}) {
                issued { code } userErrors { __typename } } }
              """
                  .formatted(target.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.issuePasswordReset.issued.code").isNotEmpty());

      assertThat(
              dsl.fetchCount(
                  SECURITY_AUDIT_EVENT, SECURITY_AUDIT_EVENT.OPERATION.eq("issuePasswordReset")))
          .isEqualTo(1);
    } finally {
      authTestSupport.deleteIdentity(target);
    }
  }

  @Test
  @DisplayName("Should invalidate an issuer's outstanding codes when the issuer is disabled")
  void shouldInvalidateIssuersOutstandingCodesWhenIssuerIsDisabled() throws Exception {
    var otherAdmin = authTestSupport.createAdminIdentity();
    var invitationCode = issueInvitation("invitee@example.com");

    try {
      graphql(
              authTestSupport.freshAccountBearer(otherAdmin),
              """
              mutation { disableAccount(input: {accountId: "%s"}) {
                account { enabled } userErrors { __typename } } }
              """
                  .formatted(serverAdmin.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.disableAccount.account.enabled").value(false));

      mockMvc
          .perform(
              post("/api/auth/invitation/lookup")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\": \"%s\"}".formatted(invitationCode)))
          .andExpect(status().isNotFound());
      assertThat(invitationRepository.findAll().getFirst().getStatus())
          .isEqualTo(AccountInvitationStatus.INVALIDATED);
    } finally {
      authTestSupport.deleteIdentity(otherAdmin);
    }
  }

  @Test
  @DisplayName("Should return every issuance refusal as a typed user error")
  void shouldReturnEveryIssuanceRefusalAsTypedUserError() throws Exception {
    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { issueAccountInvitation(input: {recipientEmail: "%s",
              householdId: "%s", householdRole: MEMBER, profileName: "Twin", profileKind: ADULT}) {
              issued { code } userErrors { __typename } } }
            """
                .formatted(serverAdmin.account().getEmail(), serverAdmin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.issueAccountInvitation.userErrors[0].__typename")
                .value("EmailAlreadyUsedError"));

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { issueAccountInvitation(input: {recipientEmail: "kid@example.com",
              householdId: "%s", householdRole: MEMBER, profileName: "Kid", profileKind: KID}) {
              issued { code } userErrors { __typename } } }
            """
                .formatted(serverAdmin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.issueAccountInvitation.userErrors[0].__typename")
                .value("LocalManagerRequiredError"));
  }

  private String issueInvitation(String email) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { issueAccountInvitation(input: {recipientEmail: "%s",
                  householdId: "%s", householdRole: MEMBER, profileName: "Invitee",
                  profileKind: ADULT}) {
                  issued { code invitation { status } } userErrors { __typename } } }
                """
                    .formatted(email, serverAdmin.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.issueAccountInvitation.issued.code").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(response)
        .path("data")
        .path("issueAccountInvitation")
        .path("issued")
        .path("code")
        .asString();
  }

  private String issuePasswordReset(UUID accountId) throws Exception {
    var response =
        graphql(
                authTestSupport.freshAccountBearer(serverAdmin),
                """
                mutation { issuePasswordReset(input: {accountId: "%s", reason: "support"}) {
                  issued { code } userErrors { __typename } } }
                """
                    .formatted(accountId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper
        .readTree(response)
        .path("data")
        .path("issuePasswordReset")
        .path("issued")
        .path("code")
        .asString();
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }
}
