package com.streamarr.server.services.auth;

import static com.streamarr.server.jooq.generated.tables.ServerBootstrap.SERVER_BOOTSTRAP;
import static com.streamarr.server.jooq.generated.tables.SessionProgress.SESSION_PROGRESS;
import static com.streamarr.server.jooq.generated.tables.WatchHistory.WATCH_HISTORY;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Setup Service Error Translation Integration Tests")
class SetupServiceErrorTranslationIT extends AbstractIntegrationTest {

  @Autowired private SetupService setupService;

  @Autowired private UserAccountRepository userAccountRepository;

  @Autowired private HouseholdRepository householdRepository;

  @Autowired private DSLContext dsl;

  private final List<SetupResult> completedSetups = new CopyOnWriteArrayList<>();

  @BeforeEach
  void releaseBootstrap() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();
  }

  @AfterEach
  void releaseBootstrapAndIdentities() {
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();

    for (var setup : completedSetups) {
      // No profile FK exists yet, so remapped watch rows must be swept explicitly before they
      // strand in the reused container.
      dsl.deleteFrom(SESSION_PROGRESS)
          .where(SESSION_PROGRESS.PROFILE_ID.eq(setup.profile().getId()))
          .execute();
      dsl.deleteFrom(WATCH_HISTORY)
          .where(WATCH_HISTORY.PROFILE_ID.eq(setup.profile().getId()))
          .execute();
      householdRepository.deleteById(setup.household().getId());
      userAccountRepository.deleteById(setup.admin().getId());
    }
  }

  @Test
  @DisplayName("Should surface integrity violation when it is not a duplicate email")
  void shouldSurfaceIntegrityViolationWhenItIsNotADuplicateEmail() {
    var command = commandBuilder("no-display-name").displayName(null).build();

    // A NOT NULL violation on a fresh, unclaimed server is not a competing setup; reporting it
    // as "setup already completed" would mislead the user and discard the real cause.
    assertThatThrownBy(() -> setupService.setup(command))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Should report setup completed when duplicate email races the claim")
  void shouldReportSetupCompletedWhenDuplicateEmailRacesTheClaim() {
    var winner = commandBuilder("winner").build();
    completedSetups.add(setupService.setup(winner));

    // Reopen the bootstrap claim so the loser reaches the insert — the deterministic stand-in
    // for the competing setup that already flushed the same email.
    dsl.deleteFrom(SERVER_BOOTSTRAP).execute();

    var loser = commandBuilder("loser").email(winner.email()).build();
    assertThatThrownBy(() -> setupService.setup(loser))
        .isInstanceOf(SetupAlreadyCompletedException.class);
  }

  private SetupCommand.SetupCommandBuilder commandBuilder(String marker) {
    var suffix = UUID.randomUUID().toString();
    return SetupCommand.builder()
        .email("translation-" + marker + "-" + suffix + "@example.com")
        .displayName("Translation " + marker)
        .password(UUID.randomUUID().toString())
        .householdName("Translation-Home-" + marker + "-" + suffix)
        .profileName("Translation-Profile-" + marker);
  }
}
