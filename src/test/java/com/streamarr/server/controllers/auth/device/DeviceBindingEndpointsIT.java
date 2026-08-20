package com.streamarr.server.controllers.auth.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.EsnBlockRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.servlet.ServletException;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR 0024 §Devices against real PostgreSQL and Cedar: the pairing loop binds a TV to the chosen
 * Household as a durable registration, the device-bound session may watch but never administer or
 * step up, and revocation, ESN blocks, and unsharing all remove device access at refresh.
 */
@Tag("IntegrationTest")
@DisplayName("Device Binding Endpoints Integration Tests")
class DeviceBindingEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private AuthSessionRepository sessionRepository;
  @Autowired private DeviceAuthorizationRepository authorizationRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private EsnBlockRepository esnBlockRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity approver;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    authTestSupport.claimBootstrap();
    approver = authTestSupport.createAdminIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    authTestSupport.unclaimBootstrap();
    authorizationRepository.deleteAll();
    esnBlockRepository.deleteAll();
    registrationRepository.deleteAll();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(approver);
  }

  @Test
  @DisplayName("Should bind the TV and confine it to watching when pairing completes")
  void shouldBindTvAndConfineItToWatchingWhenPairingCompletes() throws Exception {
    // The approver visits the host's Household through an active Personal Profile share.
    visit(approver, host);
    var issued = issueCode("Living Room TV", "esn-bind");
    var userCode = issued.get("userCode").asString();

    // The lookup offers both usable Households.
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/lookup")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(approver))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userCode\": \"%s\"}".formatted(userCode)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.households.length()").value(2))
        .andExpect(jsonPath("$.households[0].id").value(approver.household().getId().toString()))
        .andExpect(jsonPath("$.households[0].name").value(approver.household().getName()))
        .andExpect(jsonPath("$.households[1].id").value(host.household().getId().toString()))
        .andExpect(jsonPath("$.households[1].name").value(host.household().getName()));

    approve(userCode, host.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());

    var registration = registrationRepository.findAll().getFirst();
    assertThat(registration.getEsn()).isEqualTo("esn-bind");
    assertThat(registration.getHouseholdId()).isEqualTo(host.household().getId());
    assertThat(registration.getStatus()).isEqualTo(DeviceRegistrationStatus.ACTIVE);

    var deviceBearer = tokens.get("accessToken").asString();

    // Administration is forbidden wholesale — even for a ServerAdmin's own TV. A whole-surface
    // gate answers FORBIDDEN; a resource mutation answers through the oracle as not-found.
    graphql(
            deviceBearer,
            """
            mutation { addLibrary(input: {name: "Sneaky", filepath: "file:///tmp",
              type: MOVIE, backend: LOCAL}) {
              library { id } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    graphql(
            deviceBearer,
            """
            mutation { createProfile(input: {householdId: "%s", name: "Sneaky", kind: ADULT}) {
              profile { id } userErrors { __typename } } }
            """
                .formatted(host.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.createProfile.userErrors[0].__typename")
                .value("HouseholdNotFoundError"));
    graphql(deviceBearer, "query { __typename }").andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/auth/select-household")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + deviceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"householdId\": \"%s\"}".formatted(approver.household().getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEVICE_BOUND_SESSION"));

    mockMvc
        .perform(
            post("/api/auth/reauth")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + deviceBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"%s\"}".formatted(authTestSupport.password())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEVICE_BOUND_SESSION"));
  }

  @Test
  @DisplayName("Should reject a password change when the session is device-bound")
  void shouldRejectPasswordChangeWhenSessionDeviceBound() throws Exception {
    var issued = issueCode("Living Room TV", "esn-password");
    approve(issued.get("userCode").asString(), approver.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());

    mockMvc
        .perform(
            post("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken").asString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "currentPassword": "%s",
                      "newPassword": "a different long passphrase",
                      "cookieMode": false
                    }
                    """
                        .formatted(authTestSupport.password())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("DEVICE_BOUND_SESSION"));
  }

  @Test
  @DisplayName("Should remove device access at refresh when the registration is revoked")
  void shouldRemoveDeviceAccessAtRefreshWhenRegistrationIsRevoked() throws Exception {
    var issued = issueCode("Bedroom TV", "esn-revoke");
    approve(issued.get("userCode").asString(), approver.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());
    var registration = registrationRepository.findAll().getFirst();

    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { revokeDeviceRegistration(input: {registrationId: "%s"}) {
              registrationId userErrors { __typename } } }
            """
                .formatted(registration.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.revokeDeviceRegistration.userErrors").isEmpty());

    assertThat(registrationRepository.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": false}"
                        .formatted(tokens.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should revoke the device registration when its refresh credential is logged out")
  void shouldRevokeDeviceRegistrationWhenRefreshCredentialLoggedOut() throws Exception {
    var issued = issueCode("Kitchen TV", "esn-logout");
    approve(issued.get("userCode").asString(), approver.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());
    var registration = registrationRepository.findAll().getFirst();

    mockMvc
        .perform(
            post("/api/auth/refresh/revoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": false}"
                        .formatted(tokens.get("refreshToken").asString())))
        .andExpect(status().isNoContent());

    assertThat(registrationRepository.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
  }

  @Test
  @DisplayName("Should roll back logout when device registration revocation fails")
  void shouldRollBackLogoutWhenDeviceRegistrationRevocationFails() throws Exception {
    var issued = issueCode("Den TV", "esn-logout-rollback");
    approve(issued.get("userCode").asString(), approver.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());
    var refreshToken = tokens.get("refreshToken").asString();

    installRegistrationRevocationFailureTrigger();
    try {
      assertThatThrownBy(
              () ->
                  mockMvc.perform(
                      post("/api/auth/refresh/revoke")
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              "{\"refreshToken\": \"%s\", \"cookieMode\": false}"
                                  .formatted(refreshToken))))
          .isInstanceOf(ServletException.class);
    } finally {
      removeRegistrationRevocationFailureTrigger();
    }

    assertThat(registrationRepository.findAll().getFirst().getStatus())
        .isEqualTo(DeviceRegistrationStatus.ACTIVE);
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": false}".formatted(refreshToken)))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Should leave nothing behind when an ESN is blocked, and refuse its next pairing")
  void shouldLeaveNothingBehindWhenEsnBlockedAndRefuseItsNextPairing() throws Exception {
    var issued = issueCode("Hall TV", "esn-block");
    approve(issued.get("userCode").asString(), approver.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());

    // Blocking revokes the registration and its sessions in the same transaction (T10).
    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { blockEsn(input: {householdId: "%s", esn: "esn-block", reason: "stolen"}) {
              block { esn } userErrors { __typename } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.blockEsn.userErrors").isEmpty());

    assertThat(registrationRepository.findAll().getFirst().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": false}"
                        .formatted(tokens.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized());

    // Approval of the blocked ESN refuses; a grant approved before the block expires at poll.
    var refused = issueCode("Hall TV", "esn-block");
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/decision")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(approver))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userCode\": \"%s\", \"decision\": \"APPROVE\", \"householdId\": \"%s\"}"
                        .formatted(
                            refused.get("userCode").asString(), approver.household().getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ESN_BLOCKED"));

    var raced = issueCode("Hall TV", "esn-race");
    approve(raced.get("userCode").asString(), approver.household().getId());
    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { blockEsn(input: {householdId: "%s", esn: "esn-race", reason: "stolen"}) {
              block { esn } userErrors { __typename } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/auth/device/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\": \"%s\"}".formatted(raced.get("deviceCode").asString())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("expired_token"));
  }

  @Test
  @DisplayName("Should end the visitor's device access when the share ends")
  void shouldEndVisitorsDeviceAccessWhenShareEnds() throws Exception {
    var share = visit(approver, host);
    var issued = issueCode("Cabin TV", "esn-visit");
    approve(issued.get("userCode").asString(), host.household().getId());
    var tokens = pollSuccessfully(issued.get("deviceCode").asString());
    var registration = registrationRepository.findAll().getFirst();

    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { endProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(share.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.endProfileShare.userErrors").isEmpty());

    assertThat(registrationRepository.findById(registration.getId()).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    assertThat(sessionRepository.findAll())
        .filteredOn(session -> registration.getId().equals(session.getRegistrationId()))
        .singleElement()
        .satisfies(session -> assertThat(session.getRevokedAt()).isNotNull());
    mockMvc
        .perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"refreshToken\": \"%s\", \"cookieMode\": false}"
                        .formatted(tokens.get("refreshToken").asString())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Should list the registered devices when the caller administers the Household")
  void shouldListRegisteredDevicesWhenCallerAdministersHousehold() throws Exception {
    var issued = issueCode("Den TV", "esn-admin");
    approve(issued.get("userCode").asString(), approver.household().getId());
    pollSuccessfully(issued.get("deviceCode").asString());
    var bearer = authTestSupport.accountBearer(approver);

    graphql(
            bearer,
            """
            query { householdDevices(householdId: "%s") {
              edges { node { esn displayName status pairedAt } } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.householdDevices.edges[0].node.esn").value("esn-admin"))
        .andExpect(jsonPath("$.data.householdDevices.edges[0].node.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("Should require reauthentication when a stale ServerAdmin blocks an ESN server-wide")
  void shouldRequireReauthenticationWhenStaleServerAdminBlocksEsnServerWide() throws Exception {
    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { blockEsnServerWide(input: {esn: "esn-wide", reason: "stolen"}) {
              block { esn } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.blockEsnServerWide.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));
  }

  @Test
  @DisplayName(
      "Should administer the server-wide block when the ServerAdmin is freshly reauthenticated")
  void shouldAdministerServerWideBlockWhenServerAdminFreshlyReauthenticated() throws Exception {
    graphql(
            authTestSupport.freshAccountBearer(approver),
            """
            mutation { blockEsnServerWide(input: {esn: "esn-wide", reason: "stolen"}) {
              block { esn householdId } userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.blockEsnServerWide.block.esn").value("esn-wide"))
        .andExpect(jsonPath("$.data.blockEsnServerWide.block.householdId").doesNotExist());

    graphql(
            authTestSupport.accountBearer(approver),
            "query { serverEsnBlocks { edges { node { esn reason } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.serverEsnBlocks.edges[0].node.esn").value("esn-wide"));

    graphql(
            authTestSupport.accountBearer(approver),
            """
            mutation { unblockEsnServerWide(input: {esn: "esn-wide"}) {
              esn userErrors { __typename } } }
            """)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unblockEsnServerWide.esn").value("esn-wide"));
  }

  @Test
  @DisplayName("Should administer a Household block when the caller is its live admin")
  void shouldAdministerHouseholdBlockWhenCallerLiveAdmin() throws Exception {
    var bearer = authTestSupport.accountBearer(approver);

    graphql(
            bearer,
            """
            mutation { blockEsn(input: {householdId: "%s", esn: "esn-local", reason: "loaner"}) {
              block { esn } userErrors { __typename } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.blockEsn.userErrors").isEmpty());
    graphql(
            bearer,
            """
            query { esnBlocks(householdId: "%s") { edges { node { esn } } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.esnBlocks.edges[0].node.esn").value("esn-local"));

    graphql(
            bearer,
            """
            mutation { unblockEsn(input: {householdId: "%s", esn: "esn-local"}) {
              esn userErrors { __typename } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unblockEsn.esn").value("esn-local"));
    graphql(
            bearer,
            """
            mutation { unblockEsn(input: {householdId: "%s", esn: "esn-local"}) {
              esn userErrors { __typename } } }
            """
                .formatted(approver.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.unblockEsn.userErrors[0].__typename").value("EsnBlockNotFoundError"));
  }

  /** An active visit of the approver's Personal Profile into the host's Household. */
  private ProfileHouseholdShare visit(
      AuthTestSupport.TestIdentity visitor, AuthTestSupport.TestIdentity hosting) {
    return transactionTemplate.execute(
        _ ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(visitor.account().getPersonalProfileId())
                    .householdId(hosting.household().getId())
                    .status(ProfileShareStatus.ACTIVE)
                    .build()));
  }

  private JsonNode issueCode(String deviceName, String esn) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/device/code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"deviceName\": \"%s\", \"esn\": \"%s\"}".formatted(deviceName, esn)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private void approve(String userCode, UUID householdId) throws Exception {
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/decision")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(approver))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userCode\": \"%s\", \"decision\": \"APPROVE\", \"householdId\": \"%s\"}"
                        .formatted(userCode, householdId)))
        .andExpect(status().isOk());
  }

  private JsonNode pollSuccessfully(String deviceCode) throws Exception {
    var response =
        mockMvc
            .perform(
                post("/api/auth/device/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deviceCode\": \"%s\"}".formatted(deviceCode)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  private void installRegistrationRevocationFailureTrigger() {
    dsl.execute(
        """
        CREATE FUNCTION reject_registration_revocation() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          IF OLD.status = 'ACTIVE' AND NEW.status = 'REVOKED' THEN
            RAISE EXCEPTION 'forced registration revocation failure';
          END IF;
          RETURN NEW;
        END;
        $$
        """);
    dsl.execute(
        """
        CREATE TRIGGER test_reject_registration_revocation
        BEFORE UPDATE ON device_registration
        FOR EACH ROW EXECUTE FUNCTION reject_registration_revocation()
        """);
  }

  private void removeRegistrationRevocationFailureTrigger() {
    dsl.execute(
        "DROP TRIGGER IF EXISTS test_reject_registration_revocation ON device_registration");
    dsl.execute("DROP FUNCTION IF EXISTS reject_registration_revocation()");
  }
}
