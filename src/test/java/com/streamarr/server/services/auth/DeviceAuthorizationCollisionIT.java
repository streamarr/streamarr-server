package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.DeviceAuthorizationRepository;
import java.util.ArrayDeque;
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
@Import(DeviceAuthorizationCollisionIT.CollisionUserCodeConfig.class)
class DeviceAuthorizationCollisionIT extends AbstractIntegrationTest {

  @Autowired private DeviceAuthorizationService deviceAuthorizationService;

  @Autowired private DeviceAuthorizationRepository authorizationRepository;

  @AfterEach
  void deleteSeededRows() {
    authorizationRepository.deleteAll();
  }

  @Test
  @DisplayName("Should retry issuance when PostgreSQL rejects a duplicate user code")
  void shouldRetryIssuanceWhenPostgreSqlRejectsDuplicateUserCode() {
    assertThat(deviceAuthorizationService.issue("First").userCode()).isEqualTo("BBBB-BBBB");

    assertThat(deviceAuthorizationService.issue("Second").userCode()).isEqualTo("CCCC-CCCC");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class CollisionUserCodeConfig {

    @Bean
    @Primary
    UserCodeGenerator collisionUserCodeGenerator() {
      return new UserCodeGenerator() {
        private final ArrayDeque<String> candidates =
            new ArrayDeque<>(List.of("BBBBBBBB", "BBBBBBBB", "CCCCCCCC"));

        @Override
        public String generate() {
          return candidates.removeFirst();
        }
      };
    }
  }
}
