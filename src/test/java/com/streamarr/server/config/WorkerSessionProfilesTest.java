package com.streamarr.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Worker Session Profile Tests")
class WorkerSessionProfilesTest {

  @ParameterizedTest
  @CsvSource({"dev,9090", "test,0"})
  @DisplayName("Should enable loopback with the profile port when development or test starts")
  void shouldEnableLoopbackWithProfilePortWhenDevelopmentOrTestStarts(String profile, int port) {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(ProfileConfiguration.class)
        .withPropertyValues("spring.profiles.active=" + profile)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var properties = context.getBean(WorkerSessionProperties.class);
              assertThat(properties.loopback().enabled()).isTrue();
              assertThat(properties.loopback().port()).isEqualTo(port);
              assertThat(properties.mutualTls().enabled()).isFalse();
            });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(WorkerSessionProperties.class)
  static class ProfileConfiguration {}
}
