package com.streamarr.server.controllers.auth;

import static com.streamarr.server.jooq.generated.tables.CredentialAttempt.CREDENTIAL_ATTEMPT;
import static com.streamarr.server.support.AuthTestSupport.remoteAddr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.enums.CredentialKind;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * With framework forwarded-header support enabled, the journal records the forwarded client
 * address. The address does not affect throttling.
 */
@Tag("IntegrationTest")
@DisplayName("Forwarded Client Address Integration Tests")
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedClientAddressIT extends AbstractIntegrationTest {

  private static final Field<String> IP_ADDRESS_TEXT =
      DSL.field("host({0})", String.class, CREDENTIAL_ATTEMPT.IP_ADDRESS);
  private static final Field<Integer> IP_ADDRESS_FAMILY =
      DSL.field("family({0})", Integer.class, CREDENTIAL_ATTEMPT.IP_ADDRESS);

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
    dsl.deleteFrom(CREDENTIAL_ATTEMPT)
        .where(
            IP_ADDRESS_TEXT.in(
                forwardedAddresses().map(ForwardedAddress::expectedAddress).toList()))
        .and(CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(CredentialKind.PROFILE_MANAGER_INVITATION_CODE))
        .execute();
  }

  @ParameterizedTest
  @MethodSource("forwardedAddresses")
  @DisplayName("Should journal the forwarded client address when a trusted proxy fronts the login")
  void shouldJournalForwardedClientAddressWhenTrustedProxyFrontsLogin(ForwardedAddress forwarded)
      throws Exception {
    mockMvc
        .perform(
            proxiedRequest("/api/auth/login", forwarded)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "proxied", "cookieMode": false}
                    """
                        .formatted(identity.account().getEmail(), authTestSupport.password())))
        .andExpect(status().isOk());

    assertThat(
            dsl.select(IP_ADDRESS_TEXT, IP_ADDRESS_FAMILY)
                .from(CREDENTIAL_ATTEMPT)
                .where(CREDENTIAL_ATTEMPT.ACCOUNT_ID.eq(identity.account().getId()))
                .and(CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(CredentialKind.ACCOUNT_LOGIN))
                .fetch())
        .extracting(Record2::value1, Record2::value2)
        .containsExactly(tuple(forwarded.expectedAddress(), forwarded.expectedFamily()));
  }

  @ParameterizedTest
  @MethodSource("forwardedAddresses")
  @DisplayName("Should journal the forwarded address when a trusted proxy fronts GraphQL")
  void shouldJournalForwardedAddressWhenTrustedProxyFrontsGraphQl(ForwardedAddress forwarded)
      throws Exception {
    mockMvc
        .perform(
            proxiedRequest("/graphql", forwarded)
                .header("Authorization", "Bearer " + authTestSupport.accountBearer(identity))
                .content(
                    JsonMapper.builder()
                        .build()
                        .writeValueAsString(
                            Map.of(
                                "query",
                                """
                mutation { declineManagerInvitation(input: {code: "unknown.secret"}) { userErrors { __typename } } }
                """))))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.declineManagerInvitation.userErrors[0].__typename")
                .value("ManagerInvitationNotFoundError"));
    assertThat(
            dsl.select(IP_ADDRESS_TEXT, IP_ADDRESS_FAMILY)
                .from(CREDENTIAL_ATTEMPT)
                .where(IP_ADDRESS_TEXT.eq(forwarded.expectedAddress()))
                .and(
                    CREDENTIAL_ATTEMPT.CREDENTIAL_KIND.eq(
                        CredentialKind.PROFILE_MANAGER_INVITATION_CODE))
                .fetch())
        .extracting(Record2::value1, Record2::value2)
        .containsExactly(tuple(forwarded.expectedAddress(), forwarded.expectedFamily()));
  }

  private static MockHttpServletRequestBuilder proxiedRequest(
      String path, ForwardedAddress forwarded) {
    var request = post(path).with(remoteAddr("10.0.0.2")).contentType(MediaType.APPLICATION_JSON);
    forwarded.headers().forEach(request::header);
    return request;
  }

  private static Stream<ForwardedAddress> forwardedAddresses() {
    return Stream.of(
        new ForwardedAddress(Map.of("X-Forwarded-For", "203.0.113.19"), "203.0.113.19", 4),
        new ForwardedAddress(Map.of("X-Forwarded-For", "2001:db8::19"), "2001:db8::19", 6),
        new ForwardedAddress(Map.of("X-Forwarded-For", "[2001:db8::19]"), "2001:db8::19", 6),
        new ForwardedAddress(
            Map.of("X-Forwarded-For", "2001:db8::19, 10.0.0.3"), "2001:db8::19", 6),
        new ForwardedAddress(Map.of("X-Forwarded-For", "::ffff:203.0.113.19"), "203.0.113.19", 4),
        new ForwardedAddress(
            Map.of("X-Forwarded-For", "[fe80::19%remote-interface]"), "fe80::19", 6),
        new ForwardedAddress(
            Map.of("Forwarded", "for=\"[2001:db8::19]:4711\";proto=https"), "2001:db8::19", 6),
        new ForwardedAddress(
            Map.of("Forwarded", "for=\"[2001:0DB8:0:0:0:0:0:19]\""), "2001:db8::19", 6),
        new ForwardedAddress(
            Map.of(
                "Forwarded", "for=\"[2001:db8::19]:4711\", for=10.0.0.3",
                "X-Forwarded-For", "203.0.113.19"),
            "2001:db8::19",
            6));
  }

  private record ForwardedAddress(
      Map<String, String> headers, String expectedAddress, int expectedFamily) {}
}
