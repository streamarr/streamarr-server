package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes.PresentedCode;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupportConfig;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Credential Verification Transaction Integration Tests")
@Import({CredentialVerificationTransactionIT.ProbeConfiguration.class, AuthTestSupportConfig.class})
class CredentialVerificationTransactionIT extends AbstractIntegrationTest {

  private static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired private LoginService loginService;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private TransactionProbePasswordEncoder passwordEncoder;
  @Autowired private TransactionProbeOpaqueCodes opaqueCodes;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private DSLContext dsl;

  private UserAccount account;
  private AuthTestSupport.TestIdentity identity;
  private AuthTestSupport.TestIdentity serverAdmin;

  @AfterEach
  void deleteAccountAndCascades() {
    if (account != null) {
      authTestSupport.deleteAccount(account.getId());
    }

    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    invitationRepository.deleteAll();
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }

    if (serverAdmin != null) {
      authTestSupport.deleteIdentity(serverAdmin);
    }
  }

  @Test
  @DisplayName("Should release the database connection when password verification runs")
  void shouldReleaseDatabaseConnectionWhenPasswordVerificationRuns() {
    account =
        authTestSupport.createAccount(
            builder -> builder.passwordHash(passwordEncoder.encode(PASSWORD)));
    passwordEncoder.resetProbe();

    loginService.login(
        LoginCommand.builder()
            .email(account.getEmail())
            .password(PASSWORD)
            .deviceName("transaction-probe")
            .ipAddress("127.0.0.1")
            .build());

    assertThat(passwordEncoder.sawTransactionBoundConnection()).isFalse();
  }

  @Test
  @DisplayName("Should release the database connection when profile PIN verification runs")
  void shouldReleaseDatabaseConnectionWhenProfilePinVerificationRuns() throws Exception {
    identity = authTestSupport.createIdentity();
    var profile = identity.profile();
    profile.setPinHash(passwordEncoder.encode("2468"));
    profileRepository.save(profile);
    passwordEncoder.resetProbe();

    mockMvc
        .perform(
            post("/api/auth/select-profile")
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\": \"%s\", \"pin\": \"2468\"}".formatted(profile.getId())))
        .andExpect(status().isOk());

    assertThat(passwordEncoder.sawTransactionBoundConnection()).isFalse();
  }

  @Test
  @DisplayName("Should release the database connection when invitation code comparison runs")
  void shouldReleaseDatabaseConnectionWhenInvitationCodeComparisonRuns() throws Exception {
    serverAdmin = authTestSupport.createAdminIdentity();
    var code = issueInvitation();
    opaqueCodes.resetProbe();

    mockMvc
        .perform(
            post("/api/auth/invitation/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\"}".formatted(code)))
        .andExpect(status().isOk());

    assertThat(opaqueCodes.sawTransactionBoundConnection()).isFalse();
  }

  private String issueInvitation() throws Exception {
    var response =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + authTestSupport.accountBearer(serverAdmin))
                    .content(
                        objectMapper.writeValueAsString(
                            Map.of(
                                "query",
                                """
                                mutation { issueAccountInvitation(input: {recipientEmail: "%s",
                                  householdId: "%s", householdRole: MEMBER, profileName: "Invitee",
                                  profileKind: ADULT}) {
                                  issued { code } userErrors { __typename } } }
                                """
                                    .formatted(
                                        "invitee-" + UUID.randomUUID() + "@example.com",
                                        serverAdmin.household().getId())))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
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

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {

    @Bean
    @Primary
    TransactionProbePasswordEncoder transactionProbePasswordEncoder(DataSource dataSource) {
      var delegate =
          new PasswordEncoderConfig()
              .passwordEncoder(
                  Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build());
      return new TransactionProbePasswordEncoder(delegate, dataSource);
    }

    @Bean
    @Primary
    TransactionProbeOpaqueCodes transactionProbeOpaqueCodes(DataSource dataSource) {
      return new TransactionProbeOpaqueCodes(dataSource);
    }
  }

  /** Records whether the calling thread holds a transaction-bound connection when observed. */
  static final class ConnectionProbe {

    private final DataSource dataSource;
    private final AtomicBoolean transactionBoundConnection = new AtomicBoolean();

    ConnectionProbe(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    void observe() {
      Connection connection = DataSourceUtils.getConnection(dataSource);
      try {
        transactionBoundConnection.set(
            DataSourceUtils.isConnectionTransactional(connection, dataSource));
      } finally {
        DataSourceUtils.releaseConnection(connection, dataSource);
      }
    }

    void reset() {
      transactionBoundConnection.set(false);
    }

    boolean sawTransactionBoundConnection() {
      return transactionBoundConnection.get();
    }
  }

  static final class TransactionProbeOpaqueCodes extends OpaqueOneTimeCodes {

    private final ConnectionProbe probe;

    TransactionProbeOpaqueCodes(DataSource dataSource) {
      this.probe = new ConnectionProbe(dataSource);
    }

    @Override
    public boolean matches(PresentedCode presented, byte[] storedDigest) {
      probe.observe();
      return super.matches(presented, storedDigest);
    }

    void resetProbe() {
      probe.reset();
    }

    boolean sawTransactionBoundConnection() {
      return probe.sawTransactionBoundConnection();
    }
  }

  static final class TransactionProbePasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final ConnectionProbe probe;

    TransactionProbePasswordEncoder(PasswordEncoder delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.probe = new ConnectionProbe(dataSource);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      probe.observe();
      return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    void resetProbe() {
      probe.reset();
    }

    boolean sawTransactionBoundConnection() {
      return probe.sawTransactionBoundConnection();
    }
  }
}
