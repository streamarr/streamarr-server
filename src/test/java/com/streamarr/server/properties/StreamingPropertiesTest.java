package com.streamarr.server.properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@Tag("UnitTest")
@DisplayName("Streaming Properties Tests")
class StreamingPropertiesTest {

  private static final ApplicationContextRunner CONTEXT_RUNNER =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
          .withUserConfiguration(StreamingPropertiesConfiguration.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(StreamingProperties.class)
  static class StreamingPropertiesConfiguration {}

  @Test
  @DisplayName("Should default max concurrent transcodes to 8 when unset")
  void shouldDefaultMaxConcurrentTranscodesToEightWhenUnset() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.maxConcurrentTranscodes()).isEqualTo(8);
  }

  // ADR 0052: direct play, a copy, the copy without Dolby Vision and an encode fit one budget.
  @Test
  @DisplayName("Should permit four format attempts by default")
  void shouldPermitFourFormatAttemptsByDefault() {
    CONTEXT_RUNNER.run(
        context ->
            assertThat(context.getBean(StreamingProperties.class).maxFormatAttempts())
                .isEqualTo(4));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  @DisplayName("Should use the configured total format attempt budget when positive")
  void shouldUseTheConfiguredTotalFormatAttemptBudgetWhenPositive(int maximum) {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.max-format-attempts=" + maximum)
        .run(
            context ->
                assertThat(context.getBean(StreamingProperties.class).maxFormatAttempts())
                    .isEqualTo(maximum));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should fail startup when the total format attempt budget is not positive")
  void shouldFailStartupWhenTheTotalFormatAttemptBudgetIsNotPositive(int maximum) {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.max-format-attempts=" + maximum)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should reject max concurrent transcodes when not positive")
  void shouldRejectMaxConcurrentTranscodesWhenNotPositive(int configured) {
    var builder = StreamingProperties.builder().maxConcurrentTranscodes(configured);

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-concurrent-transcodes");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should fail startup when configured max concurrent transcodes is not positive")
  void shouldFailStartupWhenConfiguredMaxConcurrentTranscodesIsNotPositive(int configured) {
    CONTEXT_RUNNER
        .withPropertyValues("streaming.max-concurrent-transcodes=" + configured)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject target segment duration when not positive")
  void shouldRejectTargetSegmentDurationWhenNotPositive(long seconds) {
    var builder = StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(seconds));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("target-segment-duration");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject producer stall threshold when not positive")
  void shouldRejectProducerStallThresholdWhenNotPositive(long seconds) {
    var builder = StreamingProperties.builder().producerStallThreshold(Duration.ofSeconds(seconds));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("producer-stall-threshold");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject probe wait limit when not positive")
  void shouldRejectProbeWaitLimitWhenNotPositive(long seconds) {
    var builder = StreamingProperties.builder().probeWaitLimit(Duration.ofSeconds(seconds));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.probe-wait-limit");
  }

  @Test
  @DisplayName(
      "Should default the seek priority margin and hold limit to one 3 second slot when unset")
  void shouldDefaultTheSeekPriorityMarginAndHoldLimitToOne3SecondSlotWhenUnset() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.seekPriorityPositionMargin()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.seekPriorityHoldLimit()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  @DisplayName("Should reject the seek priority position margin when negative")
  void shouldRejectTheSeekPriorityPositionMarginWhenNegative() {
    var builder = StreamingProperties.builder().seekPriorityPositionMargin(Duration.ofSeconds(-1));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.seek-priority-position-margin");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject the seek priority hold limit when not positive")
  void shouldRejectTheSeekPriorityHoldLimitWhenNotPositive(long seconds) {
    var builder = StreamingProperties.builder().seekPriorityHoldLimit(Duration.ofSeconds(seconds));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.seek-priority-hold-limit");
  }

  @Test
  @DisplayName(
      "Should reject the hold-back low-water mark when it does not cover a target segment, the seek"
          + " priority hold limit and the position margin")
  void shouldRejectTheHoldBackLowWaterMarkWhenItDoesNotCoverATargetSegmentAndTheSeekMargins() {
    var builder =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(60))
            .holdBackHighWater(Duration.ofSeconds(90));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.hold-back-low-water");
  }

  @Test
  @DisplayName(
      "Should default the hold-back marks to 60 seconds high and 30 seconds low when unset")
  void shouldDefaultTheHoldBackMarksTo60SecondsHighAnd30SecondsLowWhenUnset() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.holdBackHighWater()).isEqualTo(Duration.ofSeconds(60));
    assertThat(properties.holdBackLowWater()).isEqualTo(Duration.ofSeconds(30));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  @DisplayName("Should reject the hold-back low-water mark when not positive")
  void shouldRejectTheHoldBackLowWaterMarkWhenNotPositive(long seconds) {
    var builder = StreamingProperties.builder().holdBackLowWater(Duration.ofSeconds(seconds));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.hold-back-low-water");
  }

  @ParameterizedTest
  @ValueSource(longs = {30, 20})
  @DisplayName(
      "Should reject the hold-back high-water mark when it does not exceed the low-water mark")
  void shouldRejectTheHoldBackHighWaterMarkWhenItDoesNotExceedTheLowWaterMark(long seconds) {
    var builder =
        StreamingProperties.builder()
            .holdBackHighWater(Duration.ofSeconds(seconds))
            .holdBackLowWater(Duration.ofSeconds(30));

    assertThatThrownBy(builder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("streaming.hold-back-high-water");
  }

  @Test
  @DisplayName("Should default probe wait limit to 15 seconds when null")
  void shouldDefaultProbeWaitLimitToFifteenSecondsWhenNull() {
    assertThat(StreamingProperties.builder().build().probeWaitLimit())
        .isEqualTo(Duration.ofSeconds(15));
  }

  @Test
  @DisplayName("Should default segment duration to 6 seconds when null")
  void shouldDefaultSegmentDurationToSixSecondsWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.targetSegmentDuration()).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  @DisplayName("Should default session timeout to 60 seconds when null")
  void shouldDefaultSessionTimeoutToSixtySecondsWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.sessionTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  @DisplayName("Should default segment base path when null")
  void shouldDefaultSegmentBasePathWhenNull() {
    var properties = StreamingProperties.builder().build();

    assertThat(properties.segmentBasePath()).contains("streamarr-segments");
  }

  @Test
  @DisplayName("Should default segment base path when blank")
  void shouldDefaultSegmentBasePathWhenBlank() {
    var properties = StreamingProperties.builder().segmentBasePath("  ").build();

    assertThat(properties.segmentBasePath()).contains("streamarr-segments");
  }

  @Test
  @DisplayName("Should use provided segment base path when given")
  void shouldUseProvidedSegmentBasePathWhenGiven() {
    var properties = StreamingProperties.builder().segmentBasePath("/custom/segments").build();

    assertThat(properties.segmentBasePath()).isEqualTo("/custom/segments");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "  "})
  @DisplayName("Should own no segment folder when none is configured")
  void shouldOwnNoSegmentFolderWhenNoneIsConfigured(String configured) {
    var properties = StreamingProperties.builder().segmentBasePath(configured).build();

    assertThat(properties.ownedSegmentFolder()).isEmpty();
  }

  @Test
  @DisplayName("Should own no segment folder when the shared one is configured by name")
  void shouldOwnNoSegmentFolderWhenTheSharedOneIsConfiguredByName() {
    var shared = StreamingProperties.builder().build().segmentBasePath();
    var properties = StreamingProperties.builder().segmentBasePath(shared).build();

    assertThat(properties.ownedSegmentFolder()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should own no segment folder when the shared one is configured with a dot in its path")
  void shouldOwnNoSegmentFolderWhenTheSharedOneIsConfiguredWithADotInItsPath() throws IOException {
    var shared = existingSharedSegmentFolder();
    var spelledWithADot = shared.getParent().resolve(".").resolve(shared.getFileName());
    var properties =
        StreamingProperties.builder().segmentBasePath(spelledWithADot.toString()).build();

    assertThat(properties.ownedSegmentFolder()).isEmpty();
  }

  @Test
  @DisplayName("Should own no segment folder when the shared one is configured by a relative path")
  void shouldOwnNoSegmentFolderWhenTheSharedOneIsConfiguredByARelativePath() throws IOException {
    var shared = existingSharedSegmentFolder();
    var relative = Path.of("").toAbsolutePath().relativize(shared.toAbsolutePath());
    var properties = StreamingProperties.builder().segmentBasePath(relative.toString()).build();

    assertThat(properties.ownedSegmentFolder()).isEmpty();
  }

  @Test
  @DisplayName("Should own no segment folder when a soft link to the shared one is configured")
  void shouldOwnNoSegmentFolderWhenASoftLinkToTheSharedOneIsConfigured(@TempDir Path temp)
      throws IOException {
    var link = Files.createSymbolicLink(temp.resolve("segments"), existingSharedSegmentFolder());
    var properties = StreamingProperties.builder().segmentBasePath(link.toString()).build();

    assertThat(properties.ownedSegmentFolder()).isEmpty();
  }

  @Test
  @DisplayName("Should own the segment folder when one is configured")
  void shouldOwnTheSegmentFolderWhenOneIsConfigured() {
    var properties = StreamingProperties.builder().segmentBasePath("/custom/segments").build();

    assertThat(properties.ownedSegmentFolder()).contains(Path.of("/custom/segments"));
  }

  // Every server that names no segment folder shares this one, so creating it changes nothing for
  // them.
  private static Path existingSharedSegmentFolder() throws IOException {
    return Files.createDirectories(
        Path.of(StreamingProperties.builder().build().segmentBasePath()));
  }
}
