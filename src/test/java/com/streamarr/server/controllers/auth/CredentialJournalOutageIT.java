package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR 0028 fails closed: while the credential journal cannot be written, every credential ceremony
 * answers 503 {@code CREDENTIAL_VERIFICATION_UNAVAILABLE} instead of verifying without a journal. A
 * side connection holds an {@code ACCESS EXCLUSIVE} lock on the table for the whole test, so the
 * reservation's bounded lock wait is the only thing standing between the caller and an unjournaled
 * verification.
 */
@Tag("IntegrationTest")
@DisplayName("Credential Journal Outage Integration Tests")
class CredentialJournalOutageIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;

  private final CountDownLatch tableLocked = new CountDownLatch(1);
  private final CountDownLatch releaseTable = new CountDownLatch(1);
  private ExecutorService executor;
  private Future<?> lockHolder;
  private AuthTestSupport.TestIdentity identity;

  @BeforeEach
  void lockJournalTable() throws InterruptedException {
    identity = authTestSupport.createIdentity();
    executor = Executors.newVirtualThreadPerTaskExecutor();
    lockHolder = executor.submit(this::holdJournalTableLock);
    assertThat(tableLocked.await(10, TimeUnit.SECONDS))
        .as("the credential journal should be locked before the ceremony starts")
        .isTrue();
  }

  @AfterEach
  void releaseJournalTable() throws InterruptedException, ExecutionException, TimeoutException {
    releaseTable.countDown();
    lockHolder.get(10, TimeUnit.SECONDS);
    executor.close();
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should refuse a login as unavailable when the credential journal cannot be written")
  void shouldRefuseLoginAsUnavailableWhenCredentialJournalCannotBeWritten() throws Exception {
    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"email": "%s", "password": "%s", "deviceName": "outage", "cookieMode": false}
                    """
                        .formatted(identity.account().getEmail(), authTestSupport.password())))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CREDENTIAL_VERIFICATION_UNAVAILABLE"));
  }

  @Test
  @DisplayName(
      "Should refuse a pairing lookup as unavailable when the credential journal cannot be written")
  void shouldRefusePairingLookupAsUnavailableWhenCredentialJournalCannotBeWritten()
      throws Exception {
    mockMvc
        .perform(
            post("/api/auth/device/authorizations/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity))
                .content("{\"userCode\": \"BCDF-GHJK\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("CREDENTIAL_VERIFICATION_UNAVAILABLE"));
  }

  @Test
  @DisplayName(
      "Should refuse a GraphQL invitation acceptance as unavailable when the credential journal"
          + " cannot be written")
  void shouldRefuseGraphQlInvitationAcceptanceAsUnavailableWhenCredentialJournalCannotBeWritten()
      throws Exception {
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity))
                .content(
                    """
                    {"query": "mutation { acceptManagerInvitation(input: {code: \\"unknown.secret\\"}) { invitation { status } userErrors { __typename } } }"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.errorType").value("UNAVAILABLE"))
        .andExpect(
            jsonPath("$.errors[0].extensions.code").value("CREDENTIAL_VERIFICATION_UNAVAILABLE"));
  }

  private Void holdJournalTableLock() throws SQLException, InterruptedException {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute("LOCK TABLE credential_attempt IN ACCESS EXCLUSIVE MODE");
      }

      tableLocked.countDown();
      releaseTable.await(60, TimeUnit.SECONDS);

      connection.rollback();
    }

    return null;
  }
}
