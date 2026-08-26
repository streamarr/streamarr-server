package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;
import static com.streamarr.server.support.AuthTestSupport.remoteAddr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.CredentialKind;
import com.streamarr.server.support.AuthTestSupport;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Behind a trusted reverse proxy the operator sets {@code
 * server.forward-headers-strategy=framework} and the journal must record the client the proxy
 * forwarded, not the proxy itself — otherwise every caller shares one address and the source-keyed
 * budgets collapse into a single bucket.
 */
@Tag("IntegrationTest")
@DisplayName("Forwarded Client Address Integration Tests")
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedClientAddressIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity identity;

  @BeforeEach
  void seedIdentity() {
    identity = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should journal the forwarded client address when a trusted proxy fronts the login")
  void shouldJournalForwardedClientAddressWhenTrustedProxyFrontsLogin() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .with(remoteAddr("10.0.0.2"))
                .header("X-Forwarded-For", "203.0.113.9")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "proxied", "cookieMode": false}
                    """
                        .formatted(identity.account().getEmail(), authTestSupport.password())))
        .andExpect(status().isOk());

    var ipAddressText = DSL.field("host({0})", String.class, CREDENTIAL_ATTEMPT.IP_ADDRESS);
    assertThat(
            dsl.select(ipAddressText)
                .from(CREDENTIAL_ATTEMPT)
                .where(CREDENTIAL_ATTEMPT.ACCOUNT_ID.eq(identity.account().getId()))
                .and(CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(CredentialKind.ACCOUNT_LOGIN))
                .fetch(ipAddressText))
        .containsExactly("203.0.113.9");
  }
}
