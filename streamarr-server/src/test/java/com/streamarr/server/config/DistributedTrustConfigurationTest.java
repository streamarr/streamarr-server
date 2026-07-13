package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.repositories.streaming.trust.CertificateAuthoritySigningLeaseRepository;
import com.streamarr.server.repositories.streaming.trust.InstallationTrustRepository;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityMaterial;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityStore;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityStoreException;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import com.streamarr.server.services.streaming.trust.InstallationTrustBootstrapService;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.PublicTrustBundle;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("UnitTest")
@DisplayName("Distributed Trust Configuration Tests")
class DistributedTrustConfigurationTest {

  @TempDir Path directory;

  @Test
  @DisplayName("Should create no trust runtime or secret when distributed mode is disabled")
  void shouldCreateNoTrustRuntimeOrSecretWhenDistributedModeDisabled() {
    var secret = directory.resolve("authority.p12");
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(
                DistributedTrustConfiguration.class, ApplicationPropertyBinding.class)
            .withPropertyValues(
                "streaming.distributed.ca-secret-path=" + secret,
                "streaming.distributed.signing-lease=0s",
                "streaming.distributed.bootstrap-timeout=0s");

    runner.run(
        context -> {
          assertThat(context)
              .doesNotHaveBean(InstallationTrust.class)
              .doesNotHaveBean(InstallationTrustBootstrapService.class)
              .doesNotHaveBean(CertificateAuthorityStore.class)
              .doesNotHaveBean(BuiltInCertificateAuthority.class)
              .doesNotHaveBean(CertificateAuthorityMaterial.class);
          assertThat(secret).doesNotExist();
        });
  }

  @EnableConfigurationProperties(DistributedTranscodeProperties.class)
  static class ApplicationPropertyBinding {}

  @Test
  @DisplayName("Should reject distributed mode without a certificate authority path")
  void shouldRejectDistributedModeWithoutCertificateAuthorityPath() {
    var properties =
        new DistributedTranscodeProperties(true, "", Duration.ofSeconds(30), Duration.ofMinutes(2));

    assertThat(org.assertj.core.api.Assertions.catchThrowable(properties::requiredCaSecretPath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CA secret path");
  }

  @Test
  @DisplayName("Should reject a relative certificate authority path")
  void shouldRejectRelativeCertificateAuthorityPath() {
    var properties =
        new DistributedTranscodeProperties(
            true, "authority.p12", Duration.ofSeconds(30), Duration.ofMinutes(2));

    assertThatThrownBy(properties::requiredCaSecretPath)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
  }

  @Test
  @DisplayName("Should reject parent traversal in the configured certificate authority path")
  void shouldRejectParentTraversalInConfiguredCertificateAuthorityPath() {
    var properties =
        new DistributedTranscodeProperties(
            true,
            directory.resolve("safe/../trust/authority.p12").toString(),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2));
    var secretPath = properties.requiredCaSecretPath();

    assertThatThrownBy(() -> new CertificateAuthorityStore(secretPath))
        .isInstanceOf(CertificateAuthorityStoreException.class)
        .hasMessageContaining("parent traversal");
  }

  @Test
  @DisplayName("Should use independent defaults for signing lease and bootstrap timeout")
  void shouldUseIndependentDefaultsForSigningLeaseAndBootstrapTimeout() {
    var properties = new DistributedTranscodeProperties(false, null, null, null);

    assertThat(properties.signingLease()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.bootstrapTimeout()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  @DisplayName("Should reject a non-positive bootstrap timeout")
  void shouldRejectNonPositiveBootstrapTimeout() {
    var secretPath = directory.resolve("authority.p12").toString();
    var signingLease = Duration.ofSeconds(30);

    assertThatThrownBy(
            () -> new DistributedTranscodeProperties(true, secretPath, signingLease, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bootstrap timeout");
  }

  @Test
  @DisplayName("Should reject a signing lease below database time precision")
  void shouldRejectSigningLeaseBelowDatabaseTimePrecision() {
    var secretPath = directory.resolve("authority.p12").toString();
    var subMicrosecondLease = Duration.ofSeconds(30).plusNanos(1);
    var bootstrapTimeout = Duration.ofMinutes(2);

    assertThatThrownBy(
            () ->
                new DistributedTranscodeProperties(
                    true, secretPath, subMicrosecondLease, bootstrapTimeout))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("microsecond");
  }

  @Test
  @DisplayName("Should reject distributed trust durations beyond operational bounds")
  void shouldRejectDistributedTrustDurationsBeyondOperationalBounds() {
    var secretPath = directory.resolve("authority.p12").toString();
    var excessiveSigningLease = Duration.ofMinutes(5).plusSeconds(1);
    var excessiveBootstrapTimeout = Duration.ofMinutes(10).plusSeconds(1);
    var signingLease = Duration.ofSeconds(30);
    var bootstrapTimeout = Duration.ofMinutes(2);

    assertThatThrownBy(
            () ->
                new DistributedTranscodeProperties(
                    true, secretPath, excessiveSigningLease, bootstrapTimeout))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signing lease")
        .hasMessageContaining("5 minutes");
    assertThatThrownBy(
            () ->
                new DistributedTranscodeProperties(
                    true, secretPath, signingLease, excessiveBootstrapTimeout))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bootstrap timeout")
        .hasMessageContaining("10 minutes");
  }

  @Test
  @DisplayName("Should fail startup eagerly when enabled bootstrap cannot obtain leadership")
  void shouldFailStartupEagerlyWhenEnabledBootstrapCannotObtainLeadership() {
    var secret = directory.resolve("trust/authority.p12");
    var runner =
        runnerWithUnavailableTrustBoundaries()
            .withPropertyValues(
                "streaming.distributed.enabled=true",
                "streaming.distributed.ca-secret-path=" + secret,
                "streaming.distributed.bootstrap-timeout=50ms");

    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(InstallationTrustException.class)
              .hasMessageContaining("did not obtain signing leadership");
          assertThat(secret).doesNotExist();
        });
  }

  @Test
  @DisplayName("Should fail enabled startup without a certificate authority path")
  void shouldFailEnabledStartupWithoutCertificateAuthorityPath() {
    var runner =
        runnerWithUnavailableTrustBoundaries()
            .withPropertyValues("streaming.distributed.enabled=true");

    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .rootCause()
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("CA secret path");
        });
  }

  private ApplicationContextRunner runnerWithUnavailableTrustBoundaries() {
    return new ApplicationContextRunner()
        .withUserConfiguration(DistributedTrustConfiguration.class)
        .withBean(InstallationTrustRepository.class, EmptyInstallationTrustRepository::new)
        .withBean(
            CertificateAuthoritySigningLeaseRepository.class,
            UnavailableSigningLeaseRepository::new);
  }

  private static final class EmptyInstallationTrustRepository
      implements InstallationTrustRepository {

    @Override
    public UUID installationId() {
      return UUID.fromString("6c758b20-bf6c-4970-a5db-c9b012b24cbf");
    }

    @Override
    public Instant databaseTime() {
      return Instant.parse("2026-07-12T12:00:00Z");
    }

    @Override
    public Optional<InstallationTrust> findInitialized() {
      return Optional.empty();
    }

    @Override
    public Optional<PublicTrustBundle> findBundle(UUID installationId, long version) {
      return Optional.empty();
    }

    @Override
    public boolean publishInitial(
        CertificateSigningLease lease, InitialTrustPublication publication) {
      return false;
    }
  }

  private static final class UnavailableSigningLeaseRepository
      implements CertificateAuthoritySigningLeaseRepository {

    @Override
    public Optional<CertificateSigningLease> tryAcquire(
        CertificateAuthorityOperation operation, UUID ownerId, Duration duration) {
      return Optional.empty();
    }

    @Override
    public Optional<CertificateSigningLease> renew(
        CertificateSigningLease currentLease, Duration duration) {
      return Optional.empty();
    }

    @Override
    public boolean isCurrent(CertificateSigningLease lease) {
      return false;
    }

    @Override
    public boolean release(CertificateSigningLease lease) {
      return false;
    }
  }
}
