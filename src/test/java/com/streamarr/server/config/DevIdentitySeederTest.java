package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeServerBootstrapRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.services.auth.SetupService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Dev Identity Seeder Tests")
class DevIdentitySeederTest {

  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();
  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeServerBootstrapRepository bootstrapRepository =
      new FakeServerBootstrapRepository();

  private final SetupService setupService =
      new SetupService(
          userAccountRepository,
          new FakeHouseholdRepository(),
          membershipRepository,
          new FakeProfileRepository(),
          new FakeAccountProfileRepository(membershipRepository),
          bootstrapRepository,
          new FakeSessionProgressRepository(),
          new FakeWatchHistoryRepository(),
          new PasswordEncoderConfig()
              .passwordEncoder(
                  Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build()));

  @Test
  @DisplayName("Should seed admin identity when bootstrap unclaimed")
  void shouldSeedAdminIdentityWhenBootstrapUnclaimed() {
    seeder(true).seedIdentity();

    assertThat(bootstrapRepository.isClaimed()).isTrue();
    assertThat(userAccountRepository.findByEmailIgnoreCase("dev@example.com")).isPresent();
  }

  @Test
  @DisplayName("Should skip seeding when disabled")
  void shouldSkipSeedingWhenDisabled() {
    seeder(false).seedIdentity();

    assertThat(bootstrapRepository.isClaimed()).isFalse();
    assertThat(userAccountRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should skip seeding when setup already complete")
  void shouldSkipSeedingWhenSetupAlreadyComplete() {
    bootstrapRepository.claim(UUID.randomUUID());

    seeder(true).seedIdentity();

    assertThat(userAccountRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should never log the admin password when seeding")
  void shouldNeverLogTheAdminPasswordWhenSeeding() {
    var logger = (Logger) LoggerFactory.getLogger(DevIdentitySeeder.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      seeder(true).seedIdentity();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .isNotEmpty()
        .extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(message -> message.contains("a dev passphrase"));
  }

  private DevIdentitySeeder seeder(boolean enabled) {
    return new DevIdentitySeeder(setupService, enabled, "dev@example.com", "a dev passphrase");
  }
}
