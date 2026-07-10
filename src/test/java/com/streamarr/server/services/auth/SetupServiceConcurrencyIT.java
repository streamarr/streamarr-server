package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.UserAccount.USER_ACCOUNT;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("SetupService Concurrency Integration Tests")
class SetupServiceConcurrencyIT extends AbstractIntegrationTest {

  private static final UUID PLACEHOLDER_PROFILE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private SetupService setupService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private DSLContext dsl;

  private final List<SetupResult> completedSetups = new CopyOnWriteArrayList<>();

  @BeforeEach
  void clearPlaceholderLeftovers() {
    // Leftover placeholder rows from other classes in the reused container are dead data; this
    // test owns the remap's input set.
    dsl.deleteFrom(SESSION_PROGRESS)
        .where(SESSION_PROGRESS.PROFILE_ID.eq(PLACEHOLDER_PROFILE_ID))
        .execute();
    dsl.deleteFrom(WATCH_HISTORY)
        .where(WATCH_HISTORY.PROFILE_ID.eq(PLACEHOLDER_PROFILE_ID))
        .execute();
  }

  @AfterEach
  void releaseBootstrapAndIdentities() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();

    for (var setup : completedSetups) {
      // Redundant with the profile FK cascade since V047, but kept explicit: stranded watch rows
      // in the reused container would poison later runs if the cascade ever changed.
      dsl.deleteFrom(WATCH_HISTORY)
          .where(WATCH_HISTORY.PROFILE_ID.eq(setup.profile().getId()))
          .execute();
      householdRepository.deleteById(setup.household().getId());
      userAccountRepository.deleteById(setup.admin().getId());
    }
  }

  @Test
  @DisplayName("Should allow exactly one setup when claimed concurrently")
  void shouldAllowExactlyOneSetupWhenClaimedConcurrently() {
    var suffix = String.valueOf(System.nanoTime());
    seedLegacyPlaceholderWatchRow();

    var commands =
        List.of(
            commandBuilder(suffix, "a").build(), //
            commandBuilder(suffix, "b").build());

    var executor = Executors.newFixedThreadPool(commands.size());
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(commands.size());
    var exceptions = new CopyOnWriteArrayList<Exception>();

    for (var command : commands) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              completedSetups.add(setupService.setup(command));
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

    executor.shutdown();

    assertThat(completedSetups).hasSize(1);
    assertThat(exceptions).singleElement().isInstanceOf(SetupAlreadyCompletedException.class);

    var winner = completedSetups.getFirst();
    var loserEmail =
        commands.stream()
            .map(SetupCommand::email)
            .filter(email -> !email.equals(winner.admin().getEmail()))
            .findFirst()
            .orElseThrow();

    // The losing transaction must roll back its admin account entirely.
    assertThat(dsl.fetchExists(USER_ACCOUNT, USER_ACCOUNT.EMAIL.eq(loserEmail))).isFalse();

    // The winner's setup remaps placeholder watch rows to the first profile.
    assertThat(
            dsl.fetchExists(WATCH_HISTORY, WATCH_HISTORY.PROFILE_ID.eq(winner.profile().getId())))
        .isTrue();
    assertThat(dsl.fetchExists(WATCH_HISTORY, WATCH_HISTORY.PROFILE_ID.eq(PLACEHOLDER_PROFILE_ID)))
        .isFalse();
  }

  @Test
  @DisplayName("Should report setup completed when identical setup requests race")
  void shouldReportSetupCompletedWhenIdenticalSetupRequestsRace() {
    var suffix = String.valueOf(System.nanoTime());
    var command = commandBuilder(suffix, "same").build();
    var commands = List.of(command, command);
    var executor = Executors.newFixedThreadPool(commands.size());
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(commands.size());
    var exceptions = new CopyOnWriteArrayList<Exception>();

    for (var repeatedCommand : commands) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              completedSetups.add(setupService.setup(repeatedCommand));
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

    executor.shutdown();

    assertThat(completedSetups).hasSize(1);
    assertThat(exceptions).singleElement().isInstanceOf(SetupAlreadyCompletedException.class);
  }

  /**
   * Legacy placeholder rows predate the NOT VALID profile FK, so they can no longer be written
   * through normal inserts — exactly as designed. Recreate the pre-migration state by bypassing
   * constraint checks for one statement (the container user is a superuser).
   */
  private void seedLegacyPlaceholderWatchRow() {
    dsl.connection(
        connection -> {
          try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
            statement.execute(
                "INSERT INTO watch_history (profile_id, collectable_id, watched_at,"
                    + " duration_seconds) VALUES ('"
                    + PLACEHOLDER_PROFILE_ID
                    + "', '"
                    + UUID.randomUUID()
                    + "', now(), 3600)");
            statement.execute("SET session_replication_role = DEFAULT");
          }
        });
  }

  private SetupCommand.SetupCommandBuilder commandBuilder(String suffix, String marker) {
    return SetupCommand.builder()
        .email("setup-" + marker + "-" + suffix + "@example.com")
        .displayName("Concurrent Admin " + marker)
        .password("correct horse battery staple")
        .householdName("Home-" + marker + "-" + suffix)
        .profileName("Profile-" + marker);
  }
}
