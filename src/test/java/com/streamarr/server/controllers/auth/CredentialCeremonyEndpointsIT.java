package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.HouseholdFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import lombok.Builder;
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
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileManagerInvitationRepository managerInvitationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;

  private AuthTestSupport.TestIdentity serverAdmin;

  @BeforeEach
  void setUp() {
    serverAdmin = authTestSupport.createAdminIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    managerInvitationRepository.deleteAll();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    userAccountRepository
        .findByEmailIgnoreCase("invitee@example.com")
        .ifPresent(created -> authTestSupport.deleteAccount(created.getId()));
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should complete the invitation loop when the code is accepted")
  void shouldCompleteInvitationLoopWhenCodeIsAccepted() throws Exception {
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
  @DisplayName("Should promote only the first accepted invitation when the Household is empty")
  void shouldPromoteOnlyFirstAcceptedInvitationWhenHouseholdIsEmpty() throws Exception {
    var household =
        householdRepository.saveAndFlush(
            HouseholdFixture.defaultHouseholdBuilder().name("Bootstrap Home").build());
    try {
      var firstCode =
          issueInvitation(
              invitationBuilder("bootstrap-one@example.com")
                  .householdId(household.getId())
                  .profileName("Bootstrap One")
                  .build());
      var secondCode =
          issueInvitation(
              invitationBuilder("bootstrap-two@example.com")
                  .householdId(household.getId())
                  .profileName("Bootstrap Two")
                  .build());

      acceptInvitation(firstCode, "Bootstrap One");
      acceptInvitation(secondCode, "Bootstrap Two");

      assertThat(
              userAccountRepository
                  .findByEmailIgnoreCase("bootstrap-one@example.com")
                  .orElseThrow()
                  .getHouseholdRole())
          .isEqualTo(HouseholdRole.ADMIN);
      assertThat(
              userAccountRepository
                  .findByEmailIgnoreCase("bootstrap-two@example.com")
                  .orElseThrow()
                  .getHouseholdRole())
          .isEqualTo(HouseholdRole.MEMBER);
    } finally {
      userAccountRepository
          .findByEmailIgnoreCase("bootstrap-one@example.com")
          .ifPresent(account -> authTestSupport.deleteAccount(account.getId()));
      if (householdRepository.existsById(household.getId())) {
        householdRepository.deleteById(household.getId());
      }
    }
  }

  @Test
  @DisplayName(
      "Should promote exactly one invitation when invitations are accepted concurrently into an empty Household")
  void shouldPromoteExactlyOneInvitationWhenAcceptedConcurrentlyIntoEmptyHousehold()
      throws Exception {
    var household =
        householdRepository.saveAndFlush(
            HouseholdFixture.defaultHouseholdBuilder().name("Concurrent Bootstrap Home").build());
    try {
      var firstCode =
          issueInvitation(
              invitationBuilder("concurrent-one@example.com")
                  .householdId(household.getId())
                  .profileName("Concurrent One")
                  .build());
      var secondCode =
          issueInvitation(
              invitationBuilder("concurrent-two@example.com")
                  .householdId(household.getId())
                  .profileName("Concurrent Two")
                  .build());
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var guardLocked = new CountDownLatch(1);
      var releaseGuard = new CountDownLatch(1);

      try {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
          var guard =
              executor.submit(
                  () -> holdHouseholdGuard(household.getId(), guardLocked, releaseGuard));
          assertThat(guardLocked.await(10, TimeUnit.SECONDS)).isTrue();
          var first =
              executor.submit(
                  () ->
                      acceptWhenStarted(
                          concurrentAcceptanceBuilder(firstCode, "Concurrent One")
                              .ready(ready)
                              .start(start)
                              .build()));
          var second =
              executor.submit(
                  () ->
                      acceptWhenStarted(
                          concurrentAcceptanceBuilder(secondCode, "Concurrent Two")
                              .ready(ready)
                              .start(start)
                              .build()));
          assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
          start.countDown();
          await()
              .atMost(Duration.ofSeconds(10))
              .untilAsserted(() -> assertThat(waitingHouseholdGuardLocks()).isEqualTo(2));
          releaseGuard.countDown();

          assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(201);
          assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(201);
          guard.get(10, TimeUnit.SECONDS);
        }
      } finally {
        releaseGuard.countDown();
      }

      assertThat(userAccountRepository.findByHouseholdId(household.getId()))
          .extracting(account -> account.getHouseholdRole())
          .containsExactlyInAnyOrder(HouseholdRole.ADMIN, HouseholdRole.MEMBER);
    } finally {
      userAccountRepository
          .findByEmailIgnoreCase("concurrent-one@example.com")
          .ifPresent(account -> authTestSupport.deleteAccount(account.getId()));
      if (householdRepository.existsById(household.getId())) {
        householdRepository.deleteById(household.getId());
      }
    }
  }

  @Test
  @DisplayName("Should sanitize the invitation User-Agent when the session device name is stored")
  void shouldSanitizeInvitationUserAgentWhenSessionDeviceNameIsStored() throws Exception {
    var code = issueInvitation("invitee@example.com");
    var userAgent = "\u202e" + "🎬".repeat(80);

    mockMvc
        .perform(
            post("/api/auth/invitation/accept")
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code": "%s", "displayName": "Invitee", \
                    "password": "a strong passphrase", "cookieMode": false}
                    """
                        .formatted(code)))
        .andExpect(status().isCreated());

    var account = userAccountRepository.findByEmailIgnoreCase("invitee@example.com").orElseThrow();
    assertThat(authSessionRepository.findByAccountId(account.getId()))
        .singleElement()
        .extracting(session -> session.getDeviceName())
        .isEqualTo("🎬".repeat(64));
  }

  @Test
  @DisplayName("Should default the backward invitation page size when only before is provided")
  void shouldDefaultBackwardInvitationPageSizeWhenOnlyBeforeIsProvided() throws Exception {
    issueInvitation("oldest@example.com");
    issueInvitation("middle@example.com");
    issueInvitation("newest@example.com");

    var firstPage =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                query { accountInvitations(first: 2) {
                  edges { cursor node { recipientEmail } }
                } }
                """)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var before =
        objectMapper
            .readTree(firstPage)
            .path("data")
            .path("accountInvitations")
            .path("edges")
            .path(1)
            .path("cursor")
            .asString();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            query { accountInvitations(before: "%s") {
              edges { node { recipientEmail } }
            } }
            """
                .formatted(before))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.accountInvitations.edges[0].node.recipientEmail")
                .value("newest@example.com"));
  }

  @Test
  @DisplayName(
      "Should return a typed conflict and preserve the invitation when its email is claimed")
  void shouldReturnTypedConflictAndPreserveInvitationWhenItsEmailIsClaimed() throws Exception {
    var code = issueInvitation("invitee@example.com");
    var competingAccount =
        authTestSupport.createAccount(builder -> builder.email("invitee@example.com"));
    try {
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
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("INVITATION_EMAIL_ALREADY_USED"));

      authTestSupport.deleteAccount(competingAccount.getId());

      acceptInvitation(code, "Invitee");
      assertThat(userAccountRepository.findByEmailIgnoreCase("invitee@example.com")).isPresent();
    } finally {
      if (userAccountRepository.existsById(competingAccount.getId())) {
        authTestSupport.deleteAccount(competingAccount.getId());
      }
    }
  }

  @Test
  @DisplayName("Should leave no Account when an invitation is declined")
  void shouldLeaveNoAccountWhenInvitationIsDeclined() throws Exception {
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
  @DisplayName("Should list and cancel only pending invitations when the caller is ServerAdmin")
  void shouldListAndCancelOnlyPendingInvitationsWhenCallerIsServerAdmin() throws Exception {
    issueInvitation("invitee@example.com");

    var listed =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                query { accountInvitations(first: 10) { edges { node { id recipientEmail status } } } }
                """)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.accountInvitations.edges[0].node.recipientEmail")
                    .value("invitee@example.com"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var invitationId =
        objectMapper
            .readTree(listed)
            .path("data")
            .path("accountInvitations")
            .path("edges")
            .path(0)
            .path("node")
            .path("id")
            .asString();

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { cancelAccountInvitation(input: {invitationId: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(invitationId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cancelAccountInvitation.invitation.status").value("CANCELED"));

    graphql(
            authTestSupport.accountBearer(serverAdmin),
            """
            mutation { cancelAccountInvitation(input: {invitationId: "%s"}) {
              invitation { status } userErrors { __typename } } }
            """
                .formatted(invitationId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.cancelAccountInvitation.userErrors[0].__typename")
                .value("InvitationNotPendingError"));
  }

  @Test
  @DisplayName("Should omit token bodies when an invitation is accepted in cookie mode")
  void shouldOmitTokenBodiesWhenInvitationIsAcceptedInCookieMode() throws Exception {
    var code = issueInvitation("invitee@example.com");

    var response =
        mockMvc
            .perform(
                post("/api/auth/invitation/accept")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"code": "%s", "displayName": "Invitee",                         "password": "a strong passphrase", "cookieMode": true}
                        """
                            .formatted(code)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn()
            .getResponse();
    assertThat(response.getCookie("streamarr_access")).isNotNull();
    assertThat(response.getCookie("streamarr_refresh")).isNotNull();
  }

  @Test
  @DisplayName(
      "Should revoke refresh and create no session when a reset is redeemed while disabled")
  void shouldRevokeRefreshAndCreateNoSessionWhenResetIsRedeemedWhileDisabled() throws Exception {
    var locked = authTestSupport.createIdentity();
    try {
      var code = issuePasswordReset(locked.account().getId());
      var sessionIdsBefore =
          authSessionRepository.findByAccountId(locked.account().getId()).stream()
              .map(session -> session.getId())
              .toList();
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

      assertThat(authSessionRepository.findByAccountId(locked.account().getId()))
          .hasSameSizeAs(sessionIdsBefore)
          .allSatisfy(
              session -> {
                assertThat(sessionIdsBefore).contains(session.getId());
                assertThat(session.getRevokedAt()).isNotNull();
              });

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
  @DisplayName("Should invalidate an issuer's reset codes when the issuer is deleted")
  void shouldInvalidateIssuersResetCodesWhenIssuerIsDeleted() throws Exception {
    var otherAdmin = authTestSupport.createAdminIdentity();
    var target = authTestSupport.createIdentity();
    var backupAdmin = residentOf(serverAdmin.household().getId());
    try {
      var code = issuePasswordReset(target.account().getId());

      graphql(
              authTestSupport.freshAccountBearer(otherAdmin),
              """
              mutation { deleteAccount(input: {accountId: "%s", profileDisposition: ERASE,
                reason: "issuer left"}) { accountId userErrors { __typename } } }
              """
                  .formatted(serverAdmin.account().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.deleteAccount.userErrors").isEmpty());

      mockMvc
          .perform(
              post("/api/auth/password-reset/redeem")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"code": "%s", "newPassword": "a brand new passphrase"}
                      """
                          .formatted(code)))
          .andExpect(status().isNotFound());
    } finally {
      authTestSupport.deleteIdentity(target);
      authTestSupport.deleteIdentity(otherAdmin);
      authTestSupport.deleteAccount(backupAdmin.getId());
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
  @DisplayName("Should invalidate an issuer's outstanding reset codes when the issuer is deleted")
  void shouldInvalidateIssuersOutstandingResetCodesWhenIssuerIsDeleted() throws Exception {
    var target = authTestSupport.createIdentity();
    var survivingAdmin = authTestSupport.createAdminIdentity();
    try {
      var code = issuePasswordReset(target.account().getId());

      graphql(
              authTestSupport.freshAccountBearer(survivingAdmin),
              """
              mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing",
                finalAccount: {choice: DELETE}}) {
                householdId userErrors { __typename } } }
              """
                  .formatted(serverAdmin.household().getId()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors").doesNotExist())
          .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());

      mockMvc
          .perform(
              post("/api/auth/password-reset/redeem")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"code": "%s", "newPassword": "a brand new passphrase"}
                      """
                          .formatted(code)))
          .andExpect(status().isNotFound());
    } finally {
      authTestSupport.deleteIdentity(target);
      authTestSupport.deleteIdentity(survivingAdmin);
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

  @Test
  @DisplayName(
      "Should connect the Profile, end visits, reoffer, and invalidate manager invitations when a CONNECT invitation is accepted")
  void
      shouldConnectProfileEndVisitsReofferAndInvalidateManagerInvitationsWhenConnectInvitationIsAccepted()
          throws Exception {
    var previousHost = authTestSupport.createIdentity();
    try {
      var orphan = orphanVisiting(previousHost.household().getId());
      var code = issueConnectInvitation(orphan.getId(), previousHost.household().getId());
      var managerInvitation =
          managerInvitationRepository.saveAndFlush(
              ProfileManagerInvitation.builder()
                  .profileId(orphan.getId())
                  .profileName(orphan.getName())
                  .inviterAccountId(serverAdmin.account().getId())
                  .inviterDisplayName(serverAdmin.account().getDisplayName())
                  .recipientAccountId(previousHost.account().getId())
                  .recipientEmail(previousHost.account().getEmail())
                  .expiresAt(Instant.now().plusSeconds(3600))
                  .publicId(UUID.randomUUID().toString())
                  .secretDigest(new byte[] {1})
                  .build());

      mockMvc
          .perform(
              post("/api/auth/invitation/lookup")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"code\": \"%s\"}".formatted(code)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.mode").value("CONNECT"))
          .andExpect(jsonPath("$.profileName").value("Grandpa Joe"))
          .andExpect(
              jsonPath("$.remainingManagers[0]").value(serverAdmin.account().getDisplayName()))
          .andExpect(jsonPath("$.endingHouseholds[0]").value(previousHost.household().getName()))
          .andExpect(jsonPath("$.reofferHouseholds[0]").value(previousHost.household().getName()));

      mockMvc
          .perform(
              post("/api/auth/invitation/accept")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"code": "%s", "displayName": "Joe", \
                      "password": "a strong passphrase", "cookieMode": false}
                      """
                          .formatted(code)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.accessToken").isNotEmpty());

      var connected =
          userAccountRepository.findByEmailIgnoreCase("invitee@example.com").orElseThrow();
      assertThat(connected.getPersonalProfileId()).isEqualTo(orphan.getId());
      var shares = shareRepository.findByProfileId(orphan.getId());
      var home =
          shares.stream()
              .filter(share -> share.getHouseholdId().equals(serverAdmin.household().getId()))
              .findFirst()
              .orElseThrow();
      assertThat(home.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
      assertThat(home.isStructural()).isTrue();
      var visits =
          shares.stream()
              .filter(share -> share.getHouseholdId().equals(previousHost.household().getId()))
              .toList();
      assertThat(visits)
          .extracting(ProfileHouseholdShare::getStatus)
          .containsExactlyInAnyOrder(ProfileShareStatus.ENDED, ProfileShareStatus.PENDING);
      var reoffered =
          visits.stream()
              .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
              .findFirst()
              .orElseThrow();
      assertThat(reoffered.getOfferedByAccountId()).isEqualTo(connected.getId());
      assertThat(
              managerInvitationRepository
                  .findById(managerInvitation.getId())
                  .orElseThrow()
                  .getStatus())
          .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);

      // The linked Profile can never be connected again.
      graphql(
              authTestSupport.accountBearer(serverAdmin),
              """
              mutation { issueAccountInvitation(input: {recipientEmail: "second@example.com",
                householdId: "%s", householdRole: MEMBER, mode: CONNECT, profileId: "%s"}) {
                issued { code } userErrors { __typename } } }
              """
                  .formatted(serverAdmin.household().getId(), orphan.getId()))
          .andExpect(status().isOk())
          .andExpect(
              jsonPath("$.data.issueAccountInvitation.userErrors[0].__typename")
                  .value("ProfileAlreadyLinkedError"));
    } finally {
      managerInvitationRepository.deleteAll();
      userAccountRepository
          .findByEmailIgnoreCase("invitee@example.com")
          .ifPresent(created -> authTestSupport.deleteAccount(created.getId()));
      authTestSupport.deleteIdentity(previousHost);
    }
  }

  /** An unlinked Profile managed by the admin, at home and actively visiting one Household. */
  private Profile orphanVisiting(UUID visitedHouseholdId) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(serverAdmin.household().getId())
                      .name("Grandpa Joe")
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(serverAdmin.account().getId())
                  .profileId(profile.getId())
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(serverAdmin.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(visitedHouseholdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private UserAccount residentOf(UUID householdId) {
    return transactionTemplate.execute(
        _ -> {
          var profile =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder().householdId(householdId).build());
          var account =
              userAccountRepository.saveAndFlush(
                  AccountFixture.defaultAccountBuilder()
                      .householdId(householdId)
                      .householdRole(HouseholdRole.ADMIN)
                      .personalProfileId(profile.getId())
                      .build());
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(householdId)
                  .status(ProfileShareStatus.ACTIVE)
                  .structural(true)
                  .build());
          return account;
        });
  }

  private String issueConnectInvitation(UUID profileId, UUID reofferHouseholdId) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { issueAccountInvitation(input: {recipientEmail: "invitee@example.com",
                  householdId: "%s", householdRole: MEMBER, mode: CONNECT, profileId: "%s",
                  reofferHouseholdIds: ["%s"]}) {
                  issued { code invitation { mode profileId } } userErrors { __typename } } }
                """
                    .formatted(serverAdmin.household().getId(), profileId, reofferHouseholdId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(
                jsonPath("$.data.issueAccountInvitation.issued.invitation.mode").value("CONNECT"))
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

  private String issueInvitation(String email) throws Exception {
    return issueInvitation(invitationBuilder(email).build());
  }

  private InvitationSpec.InvitationSpecBuilder invitationBuilder(String email) {
    return InvitationSpec.builder()
        .email(email)
        .householdId(serverAdmin.household().getId())
        .profileName("Invitee");
  }

  private String issueInvitation(InvitationSpec invitation) throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { issueAccountInvitation(input: {recipientEmail: "%s",
                  householdId: "%s", householdRole: MEMBER, profileName: "%s",
                  profileKind: ADULT}) {
                  issued { code invitation { status } } userErrors { __typename } } }
                """
                    .formatted(
                        invitation.email(), invitation.householdId(), invitation.profileName()))
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

  private void acceptInvitation(String code, String displayName) throws Exception {
    mockMvc
        .perform(
            post("/api/auth/invitation/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code": "%s", "displayName": "%s", \
                    "password": "a strong passphrase", "cookieMode": false}
                    """
                        .formatted(code, displayName)))
        .andExpect(status().isCreated());
  }

  private ConcurrentAcceptance.ConcurrentAcceptanceBuilder concurrentAcceptanceBuilder(
      String code, String displayName) {
    return ConcurrentAcceptance.builder().code(code).displayName(displayName);
  }

  private int acceptWhenStarted(ConcurrentAcceptance acceptance) throws Exception {
    acceptance.ready().countDown();
    if (!acceptance.start().await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("concurrent invitation acceptance did not start");
    }

    return mockMvc
        .perform(
            post("/api/auth/invitation/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"code": "%s", "displayName": "%s", \
                    "password": "a strong passphrase", "cookieMode": false}
                    """
                        .formatted(acceptance.code(), acceptance.displayName())))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private void holdHouseholdGuard(
      UUID householdId, CountDownLatch guardLocked, CountDownLatch releaseGuard) {
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement(
                "SELECT household_id FROM household_guard WHERE household_id = ? FOR UPDATE")) {
      connection.setAutoCommit(false);
      statement.setObject(1, householdId);
      statement.executeQuery();
      guardLocked.countDown();
      if (!releaseGuard.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("test did not release the Household guard lock");
      }

      connection.rollback();
    } catch (Exception exception) {
      throw new AssertionError("could not coordinate the Household guard lock", exception);
    }
  }

  private int waitingHouseholdGuardLocks() {
    return jdbcTemplate.queryForObject(
        """
        SELECT count(*)
        FROM pg_stat_activity
        WHERE wait_event_type = 'Lock'
          AND query ILIKE '%household_guard%'
        """,
        Integer.class);
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

  @Builder
  private record InvitationSpec(String email, UUID householdId, String profileName) {}

  @Builder
  private record ConcurrentAcceptance(
      String code, String displayName, CountDownLatch ready, CountDownLatch start) {}
}
