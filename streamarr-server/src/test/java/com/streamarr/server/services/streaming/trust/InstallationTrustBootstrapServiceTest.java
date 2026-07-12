package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.repositories.streaming.trust.CertificateAuthoritySigningLeaseRepository;
import com.streamarr.server.repositories.streaming.trust.InstallationTrustRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Installation Trust Bootstrap Service Tests")
class InstallationTrustBootstrapServiceTest {

  @TempDir Path directory;

  @Test
  @DisplayName("Should stop waiting at the bootstrap timeout independently of the signing lease")
  void shouldStopWaitingAtBootstrapTimeoutIndependentlyOfSigningLease() {
    var timing = new FakeBootstrapTiming();
    var bootstrapTimeout = Duration.ofMillis(250);
    var service = waitingServiceBuilder(timing).bootstrapTimeout(bootstrapTimeout).build();

    assertThatThrownBy(service::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("did not obtain signing leadership");
    assertThat(timing.elapsed()).isEqualTo(bootstrapTimeout);
  }

  @Test
  @DisplayName("Should bound bootstrap waiting when no timeout is provided")
  void shouldBoundBootstrapWaitingWhenNoTimeoutIsProvided() {
    var timing = new FakeBootstrapTiming();
    var service = waitingServiceBuilder(timing).build();

    assertThatThrownBy(service::bootstrap).isInstanceOf(InstallationTrustException.class);

    assertThat(timing.elapsed()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  @DisplayName("Should back off signing leadership retries while bootstrap waits")
  void shouldBackOffSigningLeadershipRetriesWhileBootstrapWaits() {
    var timing = new FakeBootstrapTiming();
    var service = waitingServiceBuilder(timing).bootstrapTimeout(Duration.ofMillis(350)).build();

    assertThatThrownBy(service::bootstrap).isInstanceOf(InstallationTrustException.class);

    assertThat(timing.waits())
        .containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(50));
  }

  @Test
  @DisplayName("Should fail before reading trust state when bootstrap begins interrupted")
  void shouldFailBeforeReadingTrustStateWhenBootstrapBeginsInterrupted() {
    var timing = new FakeBootstrapTiming();
    var trustRepository = new EmptyInstallationTrustRepository();
    var service =
        waitingServiceBuilder(timing, trustRepository)
            .bootstrapTimeout(Duration.ofSeconds(1))
            .build();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(service::bootstrap)
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("interrupted");
      assertThat(trustRepository.reads()).isZero();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should report interruption that occurs during the final bootstrap wait")
  void shouldReportInterruptionThatOccursDuringFinalBootstrapWait() {
    var timing = new FakeBootstrapTiming();
    timing.interruptOnNextWait();
    var service = waitingServiceBuilder(timing).bootstrapTimeout(Duration.ofMillis(100)).build();

    try {
      assertThatThrownBy(service::bootstrap)
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should reject a non-positive bootstrap timeout before reading trust state")
  void shouldRejectNonPositiveBootstrapTimeoutBeforeReadingTrustState() {
    var timing = new FakeBootstrapTiming();
    var trustRepository = new EmptyInstallationTrustRepository();
    var service =
        waitingServiceBuilder(timing, trustRepository).bootstrapTimeout(Duration.ZERO).build();

    assertThatThrownBy(service::bootstrap)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bootstrap timeout");
    assertThat(trustRepository.reads()).isZero();
  }

  @Test
  @DisplayName("Should not wait again when a trust read exhausts the bootstrap timeout")
  void shouldNotWaitAgainWhenTrustReadExhaustsBootstrapTimeout() {
    var timing = new FakeBootstrapTiming();
    var trustRepository =
        new EmptyInstallationTrustRepository(() -> timing.advance(Duration.ofMillis(200)));
    var service =
        waitingServiceBuilder(timing, trustRepository)
            .bootstrapTimeout(Duration.ofMillis(100))
            .build();

    assertThatThrownBy(service::bootstrap).isInstanceOf(InstallationTrustException.class);

    assertThat(timing.waits()).isEmpty();
  }

  @Test
  @DisplayName("Should not acquire leadership after a trust read is interrupted")
  void shouldNotAcquireLeadershipAfterTrustReadIsInterrupted() {
    var timing = new FakeBootstrapTiming();
    var trustRepository =
        new EmptyInstallationTrustRepository(() -> Thread.currentThread().interrupt());
    var signingLeaseRepository = new UnavailableSigningLeaseRepository();
    var service =
        waitingServiceBuilder(timing, trustRepository, signingLeaseRepository)
            .bootstrapTimeout(Duration.ofSeconds(1))
            .build();

    try {
      assertThatThrownBy(service::bootstrap)
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("interrupted");
      assertThat(signingLeaseRepository.acquisitionAttempts()).isZero();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should release leadership without side effects when acquisition is interrupted")
  void shouldReleaseLeadershipWithoutSideEffectsWhenAcquisitionIsInterrupted() {
    var timing = new FakeBootstrapTiming();
    var trustRepository = new EmptyInstallationTrustRepository();
    var signingLeaseRepository = new InterruptingSigningLeaseRepository();
    var service =
        waitingServiceBuilder(timing, trustRepository, signingLeaseRepository)
            .bootstrapTimeout(Duration.ofSeconds(1))
            .build();

    try {
      assertThatThrownBy(service::bootstrap)
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("interrupted");
      assertThat(trustRepository.reads()).isEqualTo(1);
      assertThat(signingLeaseRepository.releases()).isEqualTo(1);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should not publish trust when authority validation is interrupted")
  void shouldNotPublishTrustWhenAuthorityValidationIsInterrupted() {
    var timing = new FakeBootstrapTiming();
    var trustRepository = new InterruptingValidationTrustRepository();
    var signingLeaseRepository = new AvailableSigningLeaseRepository();
    var service =
        waitingServiceBuilder(timing, trustRepository, signingLeaseRepository)
            .bootstrapTimeout(Duration.ofSeconds(1))
            .build();

    try {
      assertThatThrownBy(service::bootstrap)
          .isInstanceOf(InstallationTrustException.class)
          .hasMessageContaining("interrupted");
      assertThat(trustRepository.publicationAttempted()).isFalse();
      assertThat(signingLeaseRepository.releases()).isEqualTo(1);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should release leadership without side effects when acquisition exceeds timeout")
  void shouldReleaseLeadershipWithoutSideEffectsWhenAcquisitionExceedsTimeout() {
    var timing = new FakeBootstrapTiming();
    var trustRepository = new EmptyInstallationTrustRepository();
    var signingLeaseRepository = new SlowSigningLeaseRepository(timing);
    var service =
        waitingServiceBuilder(timing, trustRepository, signingLeaseRepository)
            .bootstrapTimeout(Duration.ofSeconds(1))
            .build();

    assertThatThrownBy(service::bootstrap)
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("did not obtain signing leadership");
    assertThat(trustRepository.reads()).isEqualTo(1);
    assertThat(signingLeaseRepository.releases()).isEqualTo(1);
  }

  private InstallationTrustBootstrapService.InstallationTrustBootstrapServiceBuilder
      waitingServiceBuilder(InstallationTrustBootstrapService.BootstrapTiming timing) {
    return waitingServiceBuilder(timing, new EmptyInstallationTrustRepository());
  }

  private InstallationTrustBootstrapService.InstallationTrustBootstrapServiceBuilder
      waitingServiceBuilder(
          InstallationTrustBootstrapService.BootstrapTiming timing,
          InstallationTrustRepository trustRepository) {
    return waitingServiceBuilder(timing, trustRepository, new UnavailableSigningLeaseRepository());
  }

  private InstallationTrustBootstrapService.InstallationTrustBootstrapServiceBuilder
      waitingServiceBuilder(
          InstallationTrustBootstrapService.BootstrapTiming timing,
          InstallationTrustRepository trustRepository,
          CertificateAuthoritySigningLeaseRepository signingLeaseRepository) {
    return InstallationTrustBootstrapService.builder()
        .trustRepository(trustRepository)
        .signingLeaseRepository(signingLeaseRepository)
        .authorityStore(new CertificateAuthorityStore(directory.resolve("trust/authority.p12")))
        .certificateAuthority(new BuiltInCertificateAuthority())
        .ownerId(UUID.randomUUID())
        .leaseDuration(Duration.ofSeconds(30))
        .timing(timing);
  }

  private static final class EmptyInstallationTrustRepository
      implements InstallationTrustRepository {

    private final Runnable onRead;
    private int reads;

    EmptyInstallationTrustRepository() {
      this(() -> {});
    }

    EmptyInstallationTrustRepository(Runnable onRead) {
      this.onRead = onRead;
    }

    @Override
    public UUID installationId() {
      throw new AssertionError("No lease holder may create installation trust");
    }

    @Override
    public Instant databaseTime() {
      throw new AssertionError("No lease holder may read signing time");
    }

    @Override
    public Optional<InstallationTrust> findInitialized() {
      reads++;
      onRead.run();
      return Optional.empty();
    }

    @Override
    public boolean publishInitial(
        CertificateSigningLease lease, InitialTrustPublication publication) {
      throw new AssertionError("No lease holder may publish installation trust");
    }

    int reads() {
      return reads;
    }
  }

  private static final class UnavailableSigningLeaseRepository
      implements CertificateAuthoritySigningLeaseRepository {

    private int acquisitionAttempts;

    @Override
    public Optional<CertificateSigningLease> tryAcquire(
        CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
      acquisitionAttempts++;
      return Optional.empty();
    }

    @Override
    public Optional<CertificateSigningLease> renew(
        CertificateSigningLease currentLease, Duration duration) {
      throw new AssertionError("An unavailable lease cannot be renewed");
    }

    @Override
    public boolean isCurrent(CertificateSigningLease lease) {
      return false;
    }

    @Override
    public boolean release(CertificateSigningLease lease) {
      throw new AssertionError("An unavailable lease cannot be released");
    }

    int acquisitionAttempts() {
      return acquisitionAttempts;
    }
  }

  private static final class InterruptingSigningLeaseRepository
      implements CertificateAuthoritySigningLeaseRepository {

    private int releases;

    @Override
    public Optional<CertificateSigningLease> tryAcquire(
        CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
      Thread.currentThread().interrupt();
      var databaseTime = Instant.parse("2026-07-12T12:00:00Z");
      return Optional.of(
          CertificateSigningLease.builder()
              .operation(operation)
              .ownerId(ownerId)
              .fencingEpoch(1L)
              .databaseTime(databaseTime)
              .leaseUntil(databaseTime.plus(duration))
              .build());
    }

    @Override
    public Optional<CertificateSigningLease> renew(
        CertificateSigningLease currentLease, Duration duration) {
      throw new AssertionError("Interrupted bootstrap cannot renew leadership");
    }

    @Override
    public boolean isCurrent(CertificateSigningLease lease) {
      return false;
    }

    @Override
    public boolean release(CertificateSigningLease lease) {
      releases++;
      return true;
    }

    int releases() {
      return releases;
    }
  }

  private static final class InterruptingValidationTrustRepository
      implements InstallationTrustRepository {

    private static final UUID INSTALLATION_ID =
        UUID.fromString("7ec27eaa-7a7c-418e-8bf2-f940ca21b7df");
    private static final Instant DATABASE_TIME = Instant.parse("2026-07-12T12:00:00Z");

    private boolean publicationAttempted;

    @Override
    public UUID installationId() {
      return INSTALLATION_ID;
    }

    @Override
    public Instant databaseTime() {
      Thread.currentThread().interrupt();
      return DATABASE_TIME;
    }

    @Override
    public Optional<InstallationTrust> findInitialized() {
      return Optional.empty();
    }

    @Override
    public boolean publishInitial(
        CertificateSigningLease lease, InitialTrustPublication publication) {
      publicationAttempted = true;
      return false;
    }

    boolean publicationAttempted() {
      return publicationAttempted;
    }
  }

  private static final class AvailableSigningLeaseRepository
      implements CertificateAuthoritySigningLeaseRepository {

    private static final Instant DATABASE_TIME = Instant.parse("2026-07-12T12:00:00Z");

    private int releases;

    @Override
    public Optional<CertificateSigningLease> tryAcquire(
        CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
      return Optional.of(
          CertificateSigningLease.builder()
              .operation(operation)
              .ownerId(ownerId)
              .fencingEpoch(1L)
              .databaseTime(DATABASE_TIME)
              .leaseUntil(DATABASE_TIME.plus(duration))
              .build());
    }

    @Override
    public Optional<CertificateSigningLease> renew(
        CertificateSigningLease currentLease, Duration duration) {
      throw new AssertionError("Bootstrap does not renew its signing lease");
    }

    @Override
    public boolean isCurrent(CertificateSigningLease lease) {
      return true;
    }

    @Override
    public boolean release(CertificateSigningLease lease) {
      releases++;
      return true;
    }

    int releases() {
      return releases;
    }
  }

  private static final class SlowSigningLeaseRepository
      implements CertificateAuthoritySigningLeaseRepository {

    private final FakeBootstrapTiming timing;
    private int releases;

    SlowSigningLeaseRepository(FakeBootstrapTiming timing) {
      this.timing = timing;
    }

    @Override
    public Optional<CertificateSigningLease> tryAcquire(
        CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
      timing.advance(Duration.ofSeconds(2));
      var databaseTime = Instant.parse("2026-07-12T12:00:00Z");
      return Optional.of(
          CertificateSigningLease.builder()
              .operation(operation)
              .ownerId(ownerId)
              .fencingEpoch(1L)
              .databaseTime(databaseTime)
              .leaseUntil(databaseTime.plus(duration))
              .build());
    }

    @Override
    public Optional<CertificateSigningLease> renew(
        CertificateSigningLease currentLease, Duration duration) {
      throw new AssertionError("Timed-out bootstrap cannot renew leadership");
    }

    @Override
    public boolean isCurrent(CertificateSigningLease lease) {
      return false;
    }

    @Override
    public boolean release(CertificateSigningLease lease) {
      releases++;
      return true;
    }

    int releases() {
      return releases;
    }
  }

  private static final class FakeBootstrapTiming
      implements InstallationTrustBootstrapService.BootstrapTiming {

    private final List<Duration> waits = new ArrayList<>();
    private boolean interruptOnNextWait;
    private long now;

    @Override
    public long nanoTime() {
      return now;
    }

    @Override
    public void parkNanos(long nanoseconds) {
      waits.add(Duration.ofNanos(nanoseconds));
      now += nanoseconds;
      if (interruptOnNextWait) {
        interruptOnNextWait = false;
        Thread.currentThread().interrupt();
      }
    }

    Duration elapsed() {
      return Duration.ofNanos(now);
    }

    List<Duration> waits() {
      return List.copyOf(waits);
    }

    void interruptOnNextWait() {
      interruptOnNextWait = true;
    }

    void advance(Duration duration) {
      now += duration.toNanos();
    }
  }
}
