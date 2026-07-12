package com.streamarr.server.config;

import com.streamarr.server.repositories.streaming.trust.CertificateAuthoritySigningLeaseRepository;
import com.streamarr.server.repositories.streaming.trust.InstallationTrustRepository;
import com.streamarr.server.services.streaming.trust.BuiltInCertificateAuthority;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityStore;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import com.streamarr.server.services.streaming.trust.InstallationTrustBootstrapService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DistributedTranscodeProperties.class)
@ConditionalOnProperty(prefix = "streaming.distributed", name = "enabled", havingValue = "true")
public class DistributedTrustConfiguration {

  @Bean
  public CertificateAuthorityStore certificateAuthorityStore(
      DistributedTranscodeProperties properties) {
    return new CertificateAuthorityStore(properties.requiredCaSecretPath());
  }

  @Bean
  public BuiltInCertificateAuthority builtInCertificateAuthority() {
    return new BuiltInCertificateAuthority();
  }

  @Bean
  public InstallationTrustBootstrapService installationTrustBootstrapService(
      InstallationTrustRepository trustRepository,
      CertificateAuthoritySigningLeaseRepository signingLeaseRepository,
      CertificateAuthorityStore authorityStore,
      BuiltInCertificateAuthority certificateAuthority,
      DistributedTranscodeProperties properties) {
    return InstallationTrustBootstrapService.builder()
        .trustRepository(trustRepository)
        .signingLeaseRepository(signingLeaseRepository)
        .authorityStore(authorityStore)
        .certificateAuthority(certificateAuthority)
        .ownerId(UUID.randomUUID())
        .leaseDuration(properties.signingLease())
        .bootstrapTimeout(properties.bootstrapTimeout())
        .build();
  }

  @Bean
  public InstallationTrust installationTrust(InstallationTrustBootstrapService bootstrapService) {
    return bootstrapService.bootstrap();
  }
}
