package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AccountInvitationFixture.pendingInvitationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.security.Argon2Properties;
import com.streamarr.server.config.security.PasswordEncoderConfig;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.auth.AccountInvitationService.InvitationCodeCommand;
import com.streamarr.server.services.identity.ProfileSelectionService;
import com.streamarr.server.services.identity.SelectProfileCommand;
import com.streamarr.server.support.AuthTestSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Tag("IntegrationTest")
@DisplayName("Credential Verification Transaction Integration Tests")
@Import(CredentialVerificationTransactionIT.ProbeConfiguration.class)
class CredentialVerificationTransactionIT extends AbstractIntegrationTest {

  private static final String PASSWORD = UUID.randomUUID().toString();

  @Autowired private LoginService loginService;
  @Autowired private ProfileSelectionService profileSelectionService;
  @Autowired private AccountInvitationService invitationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionProbePasswordEncoder passwordEncoder;
  @Autowired private TransactionProbeOpaqueCodes opaqueCodes;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private AccountInvitationRepository invitationRepository;

  private UserAccount account;
  private AuthTestSupport.TestIdentity identity;
  private UUID invitationId;

  @AfterEach
  void deleteFixtures() {
    if (invitationId != null) {
      invitationRepository.deleteById(invitationId);
    }

    if (account != null) {
      authTestSupport.deleteAccount(account.getId());
    }

    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should release the database connection when password verification runs")
  void shouldReleaseDatabaseConnectionWhenPasswordVerificationRuns() {
    account =
        authTestSupport.createAccount(
            builder -> builder.passwordHash(passwordEncoder.encode(PASSWORD)));
    passwordEncoder.resetProbe();

    var login =
        loginService.login(
            LoginCommand.builder()
                .email(account.getEmail())
                .password(PASSWORD)
                .deviceName("transaction-probe")
                .ipAddress("127.0.0.1")
                .build());

    assertThat(login.account().getId()).isEqualTo(account.getId());
    assertThat(passwordEncoder.observations())
        .isNotEmpty()
        .containsOnly(TransactionObservation.NONE);
  }

  @Test
  @DisplayName("Should release the database connection when profile PIN verification runs")
  void shouldReleaseDatabaseConnectionWhenProfilePinVerificationRuns() {
    identity = authTestSupport.createIdentity();
    var profile = identity.profile();
    profile.setPinHash(passwordEncoder.encode("2468"));
    profileRepository.save(profile);
    passwordEncoder.resetProbe();

    var selected =
        profileSelectionService.selectProfile(
            authTestSupport.identityOf(identity),
            SelectProfileCommand.builder()
                .profileId(profile.getId())
                .pin("2468")
                .ipAddress("192.0.2.30")
                .build());

    assertThat(selected.profileId()).contains(profile.getId());
    assertThat(passwordEncoder.observations())
        .isNotEmpty()
        .containsOnly(TransactionObservation.NONE);
  }

  @Test
  @DisplayName("Should release the database connection when invitation code comparison runs")
  void shouldReleaseDatabaseConnectionWhenInvitationCodeComparisonRuns() {
    identity = authTestSupport.createIdentity();
    var issued = opaqueCodes.issue();
    var invitation =
        invitationRepository.saveAndFlush(
            pendingInvitationBuilder()
                .householdId(identity.household().getId())
                .householdName(identity.household().getName())
                .issuerAccountId(identity.account().getId())
                .publicId(issued.publicId())
                .secretDigest(issued.digest())
                .build());
    invitationId = invitation.getId();
    opaqueCodes.resetProbe();

    var preview =
        invitationService.lookup(
            InvitationCodeCommand.builder().code(issued.code()).ipAddress("192.0.2.30").build());

    assertThat(preview.recipientEmail()).isEqualTo(invitation.getRecipientEmail());
    assertThat(opaqueCodes.observations()).isNotEmpty().containsOnly(TransactionObservation.NONE);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class ProbeConfiguration {
    @Bean
    @Primary
    TransactionProbePasswordEncoder transactionProbePasswordEncoder(DataSource dataSource) {
      var delegate =
          new PasswordEncoderConfig()
              .passwordEncoder(
                  Argon2Properties.builder().memoryKib(4096).iterations(1).parallelism(1).build());
      return new TransactionProbePasswordEncoder(delegate, dataSource);
    }

    @Bean
    @Primary
    TransactionProbeOpaqueCodes transactionProbeOpaqueCodes(DataSource dataSource) {
      return new TransactionProbeOpaqueCodes(dataSource);
    }
  }

  private record TransactionObservation(boolean transactionActive, boolean connectionBound) {
    private static final TransactionObservation NONE = new TransactionObservation(false, false);
  }

  private static final class ConnectionProbe {
    private final DataSource dataSource;
    private final List<TransactionObservation> observations = new CopyOnWriteArrayList<>();

    private ConnectionProbe(DataSource dataSource) {
      this.dataSource = dataSource;
    }

    private void observe() {
      observations.add(
          new TransactionObservation(
              TransactionSynchronizationManager.isActualTransactionActive(),
              TransactionSynchronizationManager.hasResource(dataSource)));
    }

    private void reset() {
      observations.clear();
    }

    private List<TransactionObservation> observations() {
      return List.copyOf(observations);
    }
  }

  static final class TransactionProbeOpaqueCodes extends OpaqueOneTimeCodes {
    private final ConnectionProbe probe;

    TransactionProbeOpaqueCodes(DataSource dataSource) {
      this.probe = new ConnectionProbe(dataSource);
    }

    @Override
    public boolean matches(PresentedCode presented, byte[] storedDigest) {
      probe.observe();
      return super.matches(presented, storedDigest);
    }

    void resetProbe() {
      probe.reset();
    }

    List<TransactionObservation> observations() {
      return probe.observations();
    }
  }

  static final class TransactionProbePasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final ConnectionProbe probe;

    TransactionProbePasswordEncoder(PasswordEncoder delegate, DataSource dataSource) {
      this.delegate = delegate;
      this.probe = new ConnectionProbe(dataSource);
    }

    @Override
    public String encode(CharSequence rawPassword) {
      return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
      probe.observe();
      return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
      return delegate.upgradeEncoding(encodedPassword);
    }

    void resetProbe() {
      probe.reset();
    }

    List<TransactionObservation> observations() {
      return probe.observations();
    }
  }
}
