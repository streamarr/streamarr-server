package com.streamarr.server.controllers.auth;

import static com.streamarr.server.support.PostgresLockTestSupport.backendPid;
import static com.streamarr.server.support.PostgresLockTestSupport.cancelOnceBlocked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
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
 * Holds an {@code ACCESS EXCLUSIVE} lock on the journal from a separate connection, cancels the
 * request's journal statement once it waits on that lock, and checks that the APIs report
 * credential verification unavailable when the write fails.
 */
@Tag("IntegrationTest")
@DisplayName("Credential Journal Outage Integration Tests")
class CredentialJournalOutageIT extends AbstractIntegrationTest {

  private static final String JOURNAL_STATEMENT = "%credential_attempt%";

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity identity;
  private Connection journalLock;
  private CompletableFuture<Void> journalWriteCancelled;

  @BeforeEach
  void failTheJournalWriteBehindATableLock() throws SQLException {
    identity = authTestSupport.createIdentity();
    journalLock = dataSource.getConnection();
    journalLock.setAutoCommit(false);
    try (var statement = journalLock.createStatement()) {
      statement.execute("LOCK TABLE credential_attempt IN ACCESS EXCLUSIVE MODE");
    }

    journalWriteCancelled =
        cancelOnceBlocked(dataSource, backendPid(journalLock), JOURNAL_STATEMENT);
  }

  @AfterEach
  void releaseJournalTable() throws SQLException {
    try {
      assertThat(journalWriteCancelled)
          .as("the request's journal statement waited on the lock and was cancelled")
          .succeedsWithin(Duration.ofSeconds(10));
    } finally {
      journalLock.rollback();
      journalLock.close();
      authTestSupport.deleteIdentity(identity);
    }
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
      "Should refuse a GraphQL invitation code lookup as unavailable when the credential journal"
          + " cannot be written")
  void shouldRefuseGraphQlInvitationCodeLookupAsUnavailableWhenCredentialJournalCannotBeWritten()
      throws Exception {
    mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header(
                    HttpHeaders.AUTHORIZATION, "Bearer " + authTestSupport.accountBearer(identity))
                .content(
                    """
                    {"query": "mutation { lookUpManagerInvitationCode(input: {code: \\"unknown.secret\\"}) { invitation { id } userErrors { __typename } } }"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.errorType").value("UNAVAILABLE"))
        .andExpect(
            jsonPath("$.errors[0].extensions.code").value("CREDENTIAL_VERIFICATION_UNAVAILABLE"));
  }
}
