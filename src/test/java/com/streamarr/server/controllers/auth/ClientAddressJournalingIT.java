package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;
import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static com.streamarr.server.support.AuthTestSupport.remoteAddr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.CredentialKind;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR 0028: every driving adapter resolves the client address and hands it to the ceremony through
 * its command, so each journaled attempt records the caller rather than a default. One request per
 * surface, each from its own address, so a row's address proves which adapter captured it.
 */
@Tag("IntegrationTest")
@DisplayName("Client Address Journaling Integration Tests")
class ClientAddressJournalingIT extends AbstractIntegrationTest {

  private static final Field<String> IP_ADDRESS_TEXT =
      DSL.field("host({0})", String.class, CREDENTIAL_ATTEMPT.IP_ADDRESS);
  private static final String UNKNOWN_USER_CODE = "BCDF-GHJK";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private PasswordResetCodeRepository resetCodeRepository;
  @Autowired private UserAccountRepository userAccountRepository;

  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity serverAdmin;
  private String inviteeEmail;

  @BeforeEach
  void seedIdentities() {
    identity = authTestSupport.createIdentity();
    serverAdmin = authTestSupport.createAdminIdentity();
    inviteeEmail = "invitee-" + UUID.randomUUID() + "@example.com";
  }

  @AfterEach
  void deleteIdentities() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    resetCodeRepository.deleteAll();
    userAccountRepository
        .findByEmailIgnoreCase(inviteeEmail)
        .ifPresent(created -> authTestSupport.deleteAccount(created.getId()));
    authTestSupport.deleteIdentity(identity);
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should journal the client address when a reauthentication is attempted")
  void shouldJournalClientAddressWhenReauthenticationIsAttempted() throws Exception {
    mockMvc
        .perform(
            accountRequest("/api/auth/reauth", "198.51.100.101")
                .content("{\"password\": \"%s\"}".formatted(authTestSupport.password())))
        .andExpect(status().isOk());

    assertThat(journaledAddresses(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION, ownAccount()))
        .containsExactly("198.51.100.101");
  }

  @Test
  @DisplayName("Should journal the client address when a password change is attempted")
  void shouldJournalClientAddressWhenPasswordChangeIsAttempted() throws Exception {
    mockMvc
        .perform(
            accountRequest("/api/auth/change-password", "198.51.100.102")
                .content(
                    """
                    {"currentPassword": "%s", "newPassword": "a brand new passphrase"}
                    """
                        .formatted(authTestSupport.password())))
        .andExpect(status().isOk());

    assertThat(journaledAddresses(CredentialKind.ACCOUNT_PASSWORD_VERIFICATION, ownAccount()))
        .containsExactly("198.51.100.102");
  }

  @Test
  @DisplayName("Should journal the client address when a profile PIN is attempted")
  void shouldJournalClientAddressWhenProfilePinIsAttempted() throws Exception {
    var profile = identity.profile();
    profile.setPinHash(passwordEncoder.encode("2468"));
    profileRepository.save(profile);

    mockMvc
        .perform(
            accountRequest("/api/auth/select-profile", "198.51.100.103")
                .content("{\"profileId\": \"%s\", \"pin\": \"2468\"}".formatted(profile.getId())))
        .andExpect(status().isOk());

    assertThat(
            journaledAddresses(
                CredentialKind.PROFILE_PIN, CREDENTIAL_ATTEMPT.PROFILE_ID.eq(profile.getId())))
        .containsExactly("198.51.100.103");
  }

  @Test
  @DisplayName("Should journal the client address when an invitation is looked up")
  void shouldJournalClientAddressWhenInvitationIsLookedUp() throws Exception {
    var issued = issueInvitation();

    mockMvc
        .perform(
            anonymousRequest("/api/auth/invitation/lookup", "198.51.100.104")
                .content(codeBody(issued.code())))
        .andExpect(status().isOk());

    assertThat(journaledAddresses(CredentialKind.ACCOUNT_INVITATION_CODE, credential(issued)))
        .containsExactly("198.51.100.104");
  }

  @Test
  @DisplayName("Should journal the client address when an invitation is accepted")
  void shouldJournalClientAddressWhenInvitationIsAccepted() throws Exception {
    var issued = issueInvitation();

    mockMvc
        .perform(
            anonymousRequest("/api/auth/invitation/accept", "198.51.100.105")
                .content(
                    """
                    {"code": "%s", "displayName": "Invitee", \
                    "password": "a brand new passphrase", "cookieMode": false}
                    """
                        .formatted(issued.code())))
        .andExpect(status().isCreated());

    assertThat(journaledAddresses(CredentialKind.ACCOUNT_INVITATION_CODE, credential(issued)))
        .containsExactly("198.51.100.105");
  }

  @Test
  @DisplayName("Should journal the client address when an invitation is declined")
  void shouldJournalClientAddressWhenInvitationIsDeclined() throws Exception {
    var issued = issueInvitation();

    mockMvc
        .perform(
            anonymousRequest("/api/auth/invitation/decline", "198.51.100.106")
                .content(codeBody(issued.code())))
        .andExpect(status().isNoContent());

    assertThat(journaledAddresses(CredentialKind.ACCOUNT_INVITATION_CODE, credential(issued)))
        .containsExactly("198.51.100.106");
  }

  @Test
  @DisplayName("Should journal the client address when a password reset is redeemed")
  void shouldJournalClientAddressWhenPasswordResetIsRedeemed() throws Exception {
    var code = issuePasswordReset();

    mockMvc
        .perform(
            anonymousRequest("/api/auth/password-reset/redeem", "198.51.100.107")
                .content(
                    """
                    {"code": "%s", "newPassword": "a brand new passphrase"}
                    """
                        .formatted(code)))
        .andExpect(status().isNoContent());

    var resetCodeId =
        resetCodeRepository
            .findByPublicId(code.substring(0, code.indexOf('.')))
            .orElseThrow()
            .getId();
    assertThat(
            journaledAddresses(
                CredentialKind.PASSWORD_RESET_CODE,
                CREDENTIAL_ATTEMPT.CREDENTIAL_ID.eq(resetCodeId)))
        .containsExactly("198.51.100.107");
  }

  @Test
  @DisplayName("Should journal the client address when a pairing code is looked up")
  void shouldJournalClientAddressWhenPairingCodeIsLookedUp() throws Exception {
    mockMvc
        .perform(
            accountRequest("/api/auth/device/authorizations/lookup", "198.51.100.108")
                .content("{\"userCode\": \"%s\"}".formatted(UNKNOWN_USER_CODE)))
        .andExpect(status().isNotFound());

    assertThat(journaledAddresses(CredentialKind.DEVICE_PAIRING_CODE, ownAccount()))
        .containsExactly("198.51.100.108");
  }

  @Test
  @DisplayName("Should journal the client address when a pairing code is decided")
  void shouldJournalClientAddressWhenPairingCodeIsDecided() throws Exception {
    mockMvc
        .perform(
            accountRequest("/api/auth/device/authorizations/decision", "198.51.100.109")
                .content(
                    "{\"userCode\": \"%s\", \"decision\": \"DENY\"}".formatted(UNKNOWN_USER_CODE)))
        .andExpect(status().isNotFound());

    assertThat(journaledAddresses(CredentialKind.DEVICE_PAIRING_CODE, ownAccount()))
        .containsExactly("198.51.100.109");
  }

  @Test
  @DisplayName(
      "Should journal the client address when a manager invitation is declined over GraphQL")
  void shouldJournalClientAddressWhenManagerInvitationIsDeclinedOverGraphQl() throws Exception {
    graphql(
            authTestSupport.accountBearer(identity),
            "198.51.100.110",
            """
            mutation { declineManagerInvitation(input: {code: "unknown.secret"}) {
              invitation { status } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.declineManagerInvitation.userErrors[0].__typename")
                .value("ManagerInvitationNotFoundError"));

    assertThat(
            dsl.select(IP_ADDRESS_TEXT)
                .from(CREDENTIAL_ATTEMPT)
                .where(
                    CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(
                        CredentialKind.PROFILE_MANAGER_INVITATION_CODE))
                .orderBy(CREDENTIAL_ATTEMPT.ATTEMPTED_AT.desc())
                .limit(1)
                .fetchOne(IP_ADDRESS_TEXT))
        .isEqualTo("198.51.100.110");
  }

  private Condition ownAccount() {
    return CREDENTIAL_ATTEMPT.ACCOUNT_ID.eq(identity.account().getId());
  }

  private static Condition credential(IssuedInvitation issued) {
    return CREDENTIAL_ATTEMPT.CREDENTIAL_ID.eq(issued.invitationId());
  }

  private List<String> journaledAddresses(CredentialKind kind, Condition scope) {
    return dsl.select(IP_ADDRESS_TEXT)
        .from(CREDENTIAL_ATTEMPT)
        .where(CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(kind))
        .and(scope)
        .fetch(IP_ADDRESS_TEXT);
  }

  private MockHttpServletRequestBuilder accountRequest(String path, String ipAddress) {
    return anonymousRequest(path, ipAddress)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity));
  }

  private static MockHttpServletRequestBuilder anonymousRequest(String path, String ipAddress) {
    return post(path).with(remoteAddr(ipAddress)).contentType(MediaType.APPLICATION_JSON);
  }

  private static String codeBody(String code) {
    return "{\"code\": \"%s\"}".formatted(code);
  }

  private IssuedInvitation issueInvitation() throws Exception {
    var response =
        graphql(
                authTestSupport.accountBearer(serverAdmin),
                "198.51.100.100",
                """
                mutation { issueAccountInvitation(input: {recipientEmail: "%s",
                  householdId: "%s", householdRole: MEMBER, profileName: "Invitee",
                  profileKind: ADULT}) {
                  issued { code invitation { id } } userErrors { __typename } } }
                """
                    .formatted(inviteeEmail, serverAdmin.household().getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var issued =
        objectMapper.readTree(response).path("data").path("issueAccountInvitation").path("issued");
    return new IssuedInvitation(
        issued.path("code").asString(),
        UUID.fromString(issued.path("invitation").path("id").asString()));
  }

  private String issuePasswordReset() throws Exception {
    var response =
        graphql(
                authTestSupport.freshAccountBearer(serverAdmin),
                "198.51.100.100",
                """
                mutation { issuePasswordReset(input: {accountId: "%s", reason: "support"}) {
                  issued { code } userErrors { __typename } } }
                """
                    .formatted(identity.account().getId()))
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

  private ResultActions graphql(String bearer, String ipAddress, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .with(remoteAddr(ipAddress))
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  private record IssuedInvitation(String code, UUID invitationId) {}
}
