package com.streamarr.server.config.health;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

@Tag("UnitTest")
@DisplayName("TMDB Health Properties Tests")
class TmdbHealthPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(TmdbHealthPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(TmdbHealthProperties.class)
  static class TmdbHealthPropertiesConfiguration {}

  @Test
  @DisplayName("Should ship bounded defaults when TMDB health configuration is packaged")
  void shouldShipBoundedDefaultsWhenTmdbHealthConfigurationIsPackaged() throws IOException {
    var sources =
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));
    var application = sources.getFirst();

    assertThat(application.getProperty("tmdb.health.probe-timeout"))
        .isEqualTo("${TMDB_HEALTH_PROBE_TIMEOUT:2s}");
    assertThat(application.getProperty("tmdb.health.cache-ttl"))
        .isEqualTo("${TMDB_HEALTH_CACHE_TTL:30s}");
  }

  @Test
  @DisplayName("Should accept configuration when probe timeout is a short positive duration")
  void shouldAcceptConfigurationWhenProbeTimeoutIsShortPositiveDuration() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(2))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should accept configuration when probe timeout equals ten-second maximum")
  void shouldAcceptConfigurationWhenProbeTimeoutEqualsTenSecondMaximum() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(10))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject configuration when probe timeout is not positive")
  void shouldRejectConfigurationWhenProbeTimeoutIsNotPositive(long timeoutSeconds) {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(timeoutSeconds))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should fail startup when configured probe timeout is not positive")
  void shouldFailStartupWhenConfiguredProbeTimeoutIsNotPositive() {
    CONTEXT_RUNNER
        .withPropertyValues("tmdb.health.probe-timeout=0s", "tmdb.health.cache-ttl=30s")
        .run(context -> assertBindingFailure(context, "probeTimeout"));
  }

  @Test
  @DisplayName("Should fail startup when cache TTL exceeds one day")
  void shouldFailStartupWhenCacheTtlExceedsOneDay() {
    var excessiveTtl = Duration.ofDays(1).plusSeconds(1);

    CONTEXT_RUNNER
        .withPropertyValues("tmdb.health.probe-timeout=2s", "tmdb.health.cache-ttl=" + excessiveTtl)
        .run(context -> assertBindingFailure(context, "cacheTtl"));
  }

  @Test
  @DisplayName("Should accept configuration when cache TTL equals one-day maximum")
  void shouldAcceptConfigurationWhenCacheTtlEqualsOneDayMaximum() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(2))
            .cacheTtl(Duration.ofDays(1))
            .build();

    assertThat(VALIDATOR.validate(properties)).isEmpty();
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout exceeds ten seconds")
  void shouldRejectConfigurationWhenProbeTimeoutExceedsTenSeconds() {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(11))
            .cacheTtl(Duration.ofSeconds(30))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when probe timeout is missing")
  void shouldRejectConfigurationWhenProbeTimeoutIsMissing() {
    var properties = TmdbHealthProperties.builder().cacheTtl(Duration.ofSeconds(30)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("probeTimeout");
  }

  @Test
  @DisplayName("Should reject configuration when cache TTL is missing")
  void shouldRejectConfigurationWhenCacheTtlIsMissing() {
    var properties = TmdbHealthProperties.builder().probeTimeout(Duration.ofSeconds(2)).build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("cacheTtl");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject configuration when cache TTL is not positive")
  void shouldRejectConfigurationWhenCacheTtlIsNotPositive(long ttlSeconds) {
    var properties =
        TmdbHealthProperties.builder()
            .probeTimeout(Duration.ofSeconds(2))
            .cacheTtl(Duration.ofSeconds(ttlSeconds))
            .build();

    assertThat(VALIDATOR.validate(properties))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactly("cacheTtl");
  }

  private static void assertBindingFailure(
      AssertableApplicationContext context, String propertyName) {
    assertThat(context).hasFailed();
    assertThat(context.getStartupFailure())
        .isInstanceOf(ConfigurationPropertiesBindException.class)
        .hasRootCauseInstanceOf(BindValidationException.class)
        .hasStackTraceContaining(
            "Field error in object 'tmdb.health' on field '" + propertyName + "'");
  }
}
