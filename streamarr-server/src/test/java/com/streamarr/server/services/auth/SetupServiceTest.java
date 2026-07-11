package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.exceptions.SetupAlreadyCompletedException;
import com.streamarr.server.fakes.FakeAccountProfileRepository;
import com.streamarr.server.fakes.FakeHouseholdMembershipRepository;
import com.streamarr.server.fakes.FakeHouseholdRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeServerBootstrapRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.fixtures.SessionProgressFixture;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Tag("UnitTest")
@DisplayName("Setup Service Tests")
class SetupServiceTest {

  private static final UUID PLACEHOLDER_PROFILE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final FakeUserAccountRepository userAccountRepository = new FakeUserAccountRepository();
  private final FakeHouseholdRepository householdRepository = new FakeHouseholdRepository();
  private final FakeHouseholdMembershipRepository membershipRepository =
      new FakeHouseholdMembershipRepository();
  private final FakeProfileRepository profileRepository = new FakeProfileRepository();
  private final FakeAccountProfileRepository accountProfileRepository =
      new FakeAccountProfileRepository();
  private final FakeServerBootstrapRepository bootstrapRepository =
      new FakeServerBootstrapRepository();
  private final FakeSessionProgressRepository sessionProgressRepository =
      new FakeSessionProgressRepository();
  private final FakeWatchHistoryRepository watchHistoryRepository =
      new FakeWatchHistoryRepository();
  private final CountingPasswordEncoder passwordEncoder =
      new CountingPasswordEncoder(
          new PasswordEncoderConfig()
              .passwordEncoder(
                  Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build()));

  private final SetupService setupService =
      new SetupService(
          userAccountRepository,
          householdRepository,
          membershipRepository,
          profileRepository,
          accountProfileRepository,
          bootstrapRepository,
          sessionProgressRepository,
          watchHistoryRepository,
          passwordEncoder);

  @Test
  @DisplayName("Should create admin household and profile when bootstrap unclaimed")
  void shouldCreateAdminHouseholdAndProfileWhenBootstrapUnclaimed() {
    sessionProgressRepository.save(
        SessionProgressFixture.progressBuilder(PLACEHOLDER_PROFILE_ID, UUID.randomUUID())
            .durationSeconds(3600)
            .build());
    watchHistoryRepository.save(
        WatchHistory.builder()
            .profileId(PLACEHOLDER_PROFILE_ID)
            .collectableId(UUID.randomUUID())
            .watchedAt(Instant.now())
            .durationSeconds(7200)
            .build());

    var result = setupService.setup(defaultCommandBuilder().build());

    var admin = userAccountRepository.findById(result.admin().getId()).orElseThrow();
    assertThat(admin.getAccountRole()).isEqualTo(AccountRole.ADMIN);
    assertThat(admin.isEnabled()).isTrue();
    assertThat(admin.getEmail()).isEqualTo("admin@example.com");
    assertThat(admin.getPasswordHash()).startsWith("{argon2id}");

    var household = householdRepository.findById(result.household().getId()).orElseThrow();
    assertThat(household.getName()).isEqualTo("Home");
    assertThat(household.getDefaultRatingRegion()).isEqualTo("US");

    var membership = membershipRepository.findAll().getFirst();
    assertThat(membership.getAccountId()).isEqualTo(admin.getId());
    assertThat(membership.getHouseholdId()).isEqualTo(household.getId());
    assertThat(membership.getHouseholdRole()).isEqualTo(HouseholdRole.OWNER);
    var profile = profileRepository.findById(result.profile().getId()).orElseThrow();
    assertThat(profile.getHouseholdId()).isEqualTo(household.getId());
    assertThat(profile.getName()).isEqualTo("Andrew");

    assertThat(accountProfileRepository.findAll())
        .singleElement()
        .satisfies(
            link -> {
              assertThat(link.getAccountId()).isEqualTo(admin.getId());
              assertThat(link.getHouseholdId()).isEqualTo(household.getId());
              assertThat(link.getProfileId()).isEqualTo(profile.getId());
            });

    assertThat(sessionProgressRepository.findAll())
        .allSatisfy(progress -> assertThat(progress.getProfileId()).isEqualTo(profile.getId()));
    assertThat(watchHistoryRepository.findAll())
        .allSatisfy(history -> assertThat(history.getProfileId()).isEqualTo(profile.getId()));
  }

  @Test
  @DisplayName("Should keep other profiles watch rows when setup remaps placeholder rows")
  void shouldKeepOtherProfilesWatchRowsWhenSetupRemapsPlaceholderRows() {
    var otherProfileId = UUID.randomUUID();
    sessionProgressRepository.save(
        SessionProgressFixture.progressBuilder(otherProfileId, UUID.randomUUID())
            .durationSeconds(1800)
            .build());
    watchHistoryRepository.save(
        WatchHistory.builder()
            .profileId(otherProfileId)
            .collectableId(UUID.randomUUID())
            .watchedAt(Instant.now())
            .durationSeconds(2400)
            .build());

    setupService.setup(defaultCommandBuilder().build());

    assertThat(sessionProgressRepository.findAll())
        .allSatisfy(progress -> assertThat(progress.getProfileId()).isEqualTo(otherProfileId));
    assertThat(watchHistoryRepository.findAll())
        .allSatisfy(history -> assertThat(history.getProfileId()).isEqualTo(otherProfileId));
  }

  @Test
  @DisplayName("Should reject setup when bootstrap claimed")
  void shouldRejectSetupWhenBootstrapClaimed() {
    bootstrapRepository.claim(UUID.randomUUID());

    var command = defaultCommandBuilder().build();

    assertThatThrownBy(() -> setupService.setup(command))
        .isInstanceOf(SetupAlreadyCompletedException.class);
  }

  @Test
  @DisplayName("Should report setup completed when a competing setup already inserted the email")
  void shouldReportSetupCompletedWhenCompetingSetupAlreadyInsertedTheEmail() {
    userAccountRepository.save(
        UserAccount.builder()
            .email("admin@example.com")
            .displayName("Competing Setup Winner")
            .passwordHash("{argon2id}competing")
            .accountRole(AccountRole.ADMIN)
            .enabled(true)
            .build());
    var command = defaultCommandBuilder().build();

    assertThatThrownBy(() -> setupService.setup(command))
        .isInstanceOf(SetupAlreadyCompletedException.class);
  }

  @Test
  @DisplayName("Should surface integrity violation when constraint is not the admin email")
  void shouldSurfaceIntegrityViolationWhenConstraintIsNotTheAdminEmail() {
    var message = "duplicate key value violates unique constraint \"uq_other_constraint\"";
    var foreignViolation =
        new DataIntegrityViolationException(
            message,
            new ConstraintViolationException(
                message, new SQLException(message, "23505"), "uq_other_constraint"));
    var throwingRepository =
        new FakeUserAccountRepository() {
          @Override
          public <S extends UserAccount> S saveAndFlush(S entity) {
            throw foreignViolation;
          }
        };
    var service =
        new SetupService(
            throwingRepository,
            householdRepository,
            membershipRepository,
            profileRepository,
            accountProfileRepository,
            bootstrapRepository,
            sessionProgressRepository,
            watchHistoryRepository,
            passwordEncoder);

    var command = defaultCommandBuilder().build();

    assertThatThrownBy(() -> service.setup(command)).isSameAs(foreignViolation);
  }

  @Test
  @DisplayName("Should skip password encoding when bootstrap already claimed")
  void shouldSkipPasswordEncodingWhenBootstrapAlreadyClaimed() {
    bootstrapRepository.claim(UUID.randomUUID());

    var command = defaultCommandBuilder().build();

    assertThatThrownBy(() -> setupService.setup(command))
        .isInstanceOf(SetupAlreadyCompletedException.class);
    assertThat(passwordEncoder.encodeInvocations()).isZero();
  }

  private SetupCommand.SetupCommandBuilder defaultCommandBuilder() {
    return SetupCommand.builder()
        .email("admin@example.com")
        .displayName("Admin")
        .password("correct horse battery staple")
        .householdName("Home")
        .profileName("Andrew");
  }

  private static final class CountingPasswordEncoder implements PasswordEncoder {

    private final PasswordEncoder delegate;
    private final AtomicInteger encodeInvocations = new AtomicInteger();

    private CountingPasswordEncoder(PasswordEncoder delegate) {
      this.delegate = delegate;
    }

    @Override
    public String encode(CharSequence rawPassword) {
      encodeInvocations.incrementAndGet();
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    private int encodeInvocations() {
      return encodeInvocations.get();
    }
  }
}
