package com.streamarr.server.services.identity;

import static com.streamarr.server.support.PostgresLockTestSupport.lockRow;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static com.streamarr.server.support.ServerSettingsTestSupport.limitShareCodes;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport.RowLockTarget;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A Household holds at most the server's limit of live share codes, so of two issues that find room
 * for one more code, exactly one issues it (ADR 0024 §Share codes). The race is held at the
 * Household's row: both issues wait behind it and are released together.
 */
@Tag("IntegrationTest")
@DisplayName("Share Code Limit Race Integration Tests")
class ShareCodeLimitRaceIT extends AbstractIntegrationTest {

  private static final String WAITING_ON_HOUSEHOLD = "%household%";

  @Autowired private ShareCodeService shareCodeService;
  @Autowired private ShareCodeRepository shareCodeRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    resetServerSettings(dsl);
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    resetServerSettings(dsl);
    authTestSupport.deleteIdentity(host);
  }

  @Test
  @DisplayName("Should issue exactly one code when two issues find room for one at the same time")
  void shouldIssueExactlyOneCodeWhenTwoIssuesFindRoomForOneAtTheSameTime() throws Exception {
    limitShareCodes(dsl, 1);
    var householdRow =
        RowLockTarget.builder()
            .dataSource(dataSource)
            .table("household")
            .rowId(host.household().getId())
            .build();

    List<Outcome<NewShareCode, ShareCodeRejections.Issue>> outcomes;
    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor();
        var householdLock = lockRow(householdRow)) {
      var first = executor.submit(this::issue);
      var second = executor.submit(this::issue);
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  waitersBehind(jdbcTemplate, householdLock.backendPid(), WAITING_ON_HOUSEHOLD)
                      == 2);

      householdLock.release();
      outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }

    assertThat(outcomes).filteredOn(Outcome.Accepted.class::isInstance).hasSize(1);
    assertThat(outcomes)
        .filteredOn(Outcome.Rejected.class::isInstance)
        .containsExactly(Outcome.rejected(new ShareCodeRejections.ShareCodeLimitReached()));
    assertThat(shareCodeRepository.findAll())
        .filteredOn(shareCode -> shareCode.getHouseholdId().equals(host.household().getId()))
        .filteredOn(shareCode -> shareCode.isLiveAt(Instant.now()))
        .hasSize(1);
  }

  private Outcome<NewShareCode, ShareCodeRejections.Issue> issue() {
    return shareCodeService.issueShareCode(
        authTestSupport.identityOf(host), host.household().getId());
  }
}
