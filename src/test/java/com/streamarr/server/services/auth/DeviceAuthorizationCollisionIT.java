package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@Tag("IntegrationTest")
@DisplayName("Device Authorization Collision Integration Tests")
@Import(DeviceAuthorizationCollisionIT.CollisionCodeConfig.class)
class DeviceAuthorizationCollisionIT extends AbstractIntegrationTest {

  @Autowired private DeviceAuthorizationService deviceAuthorizationService;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @Autowired private SequenceUserCodeGenerator userCodeGenerator;

  @Autowired private SequenceSecureRandom deviceCodeRandom;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
  }

  @Test
  @DisplayName("Should retry issuance when PostgreSQL rejects a duplicate user code")
  void shouldRetryIssuanceWhenPostgreSqlRejectsDuplicateUserCode() {
    userCodeGenerator.prepare(List.of("BBBBBBBB", "BBBBBBBB", "CCCCCCCC"));
    deviceCodeRandom.prepare(List.of(filledBytes(1), filledBytes(2)));

    assertThat(deviceAuthorizationService.issue("First", "esn-1").userCode())
        .isEqualTo("BBBB-BBBB");

    assertThat(deviceAuthorizationService.issue("Second", "esn-1").userCode())
        .isEqualTo("CCCC-CCCC");
  }

  @Test
  @DisplayName("Should retry issuance when PostgreSQL rejects a duplicate device code")
  void shouldRetryIssuanceWhenPostgreSqlRejectsDuplicateDeviceCode() {
    userCodeGenerator.prepare(List.of("BBBBBBBB", "CCCCCCCC", "DDDDDDDD"));
    deviceCodeRandom.prepare(List.of(filledBytes(1), filledBytes(1), filledBytes(2)));

    var first = deviceAuthorizationService.issue("First", "esn-1");
    var second = deviceAuthorizationService.issue("Second", "esn-1");

    assertThat(second.deviceCode()).isNotEqualTo(first.deviceCode());
    assertThat(authorizationRepository.findAll()).hasSize(2);
  }

  private static byte[] filledBytes(int value) {
    var bytes = new byte[32];
    Arrays.fill(bytes, (byte) value);
    return bytes;
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CollisionCodeConfig {

    @Bean
    @Primary
    SequenceUserCodeGenerator sequenceUserCodeGenerator() {
      return new SequenceUserCodeGenerator();
    }

    @Bean
    SequenceSecureRandom sequenceSecureRandom() {
      return new SequenceSecureRandom();
    }

    @Bean
    @Primary
    DeviceCodeGenerator sequenceDeviceCodeGenerator(SequenceSecureRandom secureRandom) {
      return new DeviceCodeGenerator(secureRandom);
    }
  }

  static final class SequenceUserCodeGenerator extends UserCodeGenerator {

    private final ArrayDeque<String> candidates = new ArrayDeque<>();

    void prepare(List<String> preparedCandidates) {
      candidates.clear();
      candidates.addAll(preparedCandidates);
    }

    @Override
    public String generate() {
      return candidates.removeFirst();
    }
  }

  @SuppressWarnings("serial")
  static final class SequenceSecureRandom extends SecureRandom {

    private final ArrayDeque<byte[]> values = new ArrayDeque<>();

    void prepare(List<byte[]> preparedValues) {
      values.clear();
      values.addAll(preparedValues);
    }

    @Override
    public void nextBytes(byte[] bytes) {
      var prepared = values.removeFirst();
      if (prepared.length != bytes.length) {
        throw new IllegalArgumentException("Prepared random value has the wrong length.");
      }

      System.arraycopy(prepared, 0, bytes, 0, bytes.length);
    }
  }
}
