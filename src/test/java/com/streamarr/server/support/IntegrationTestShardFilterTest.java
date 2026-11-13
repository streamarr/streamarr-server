package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

@Tag("UnitTest")
@DisplayName("Integration Test Shard Filter Tests")
class IntegrationTestShardFilterTest {

  private static final List<String> GENERATED_CLASSES =
      IntStream.range(0, 1_000).mapToObj(index -> "generated.Generated" + index + "IT").toList();

  @BeforeEach
  @AfterEach
  void clearSelectedShard() {
    System.clearProperty(IntegrationTestShardFilter.PROPERTY);
  }

  @Test
  @DisplayName("Should keep every test when no shard is selected")
  void shouldKeepEveryTestWhenNoShardIsSelected() {
    var filter = new IntegrationTestShardFilter();

    var excluded =
        GENERATED_CLASSES.stream()
            .filter(name -> filter.apply(testMethodOf(name)).excluded())
            .toList();

    assertThat(excluded).as("classes left out when no shard is selected").isEmpty();
  }

  @Test
  @DisplayName("Should keep a test class and its nested tests in one shard when JUnit discovers it")
  void shouldKeepATestClassAndItsNestedTestsInOneShardWhenJunitDiscoversIt() {
    var keeper = IntegrationTestShards.shardOf(IntegrationTestShardFilterTest.class.getName(), 2);
    assertThat(IntegrationTestShards.shardOf(WhenEveryShardRuns.class.getName(), 2))
        .as("shard of the nested class's own name")
        .isNotEqualTo(keeper);
    var everyTest = discoveredTests();
    assertThat(everyTest).as("nested tests").anyMatch(id -> id.contains("[nested-class:"));

    var testsPerShard =
        IntStream.rangeClosed(1, 2)
            .mapToObj(
                shard -> {
                  System.setProperty(IntegrationTestShardFilter.PROPERTY, shard + "/2");
                  return discoveredTests();
                })
            .toList();

    assertThat(testsPerShard.get(keeper - 1)).as("tests in shard %s", keeper).isEqualTo(everyTest);
    assertThat(testsPerShard.get(2 - keeper)).as("tests in the other shard").isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "1", "0/2", "3/2", "1/0", "a/2", "1/2/3", "${ci.integration.shard}/2"})
  @DisplayName("Should name the property and its value when the selected shard is invalid")
  void shouldNameThePropertyAndItsValueWhenTheSelectedShardIsInvalid(String shard) {
    System.setProperty(IntegrationTestShardFilter.PROPERTY, shard);

    assertThatThrownBy(IntegrationTestShardFilter::new)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(IntegrationTestShardFilter.PROPERTY)
        .hasMessageContaining("\"" + shard + "\"");
  }

  @Test
  @DisplayName("Should register the filter when JUnit loads post-discovery filters")
  void shouldRegisterTheFilterWhenJunitLoadsPostDiscoveryFilters() {
    var registered =
        ServiceLoader.load(PostDiscoveryFilter.class).stream()
            .map(ServiceLoader.Provider::type)
            .toList();

    assertThat(registered).contains(IntegrationTestShardFilter.class);
  }

  @Nested
  @DisplayName("Filters For Every Shard Tests")
  class WhenEveryShardRuns {

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    @DisplayName("Should keep each test class in exactly one shard when every shard runs")
    void shouldKeepEachTestClassInExactlyOneShardWhenEveryShardRuns(int count) {
      var filters = everyShardOf(count);

      var keepers =
          GENERATED_CLASSES.stream()
              .collect(
                  Collectors.toMap(
                      Function.identity(), name -> shardsKeeping(filters, testMethodOf(name))));

      assertThat(keepers)
          .as("shards that keep each test class")
          .allSatisfy((name, shards) -> assertThat(shards).as(name).hasSize(1));
    }

    @Test
    @DisplayName("Should refuse a test that belongs to no test class when every shard runs")
    void shouldRefuseATestThatBelongsToNoTestClassWhenEveryShardRuns() {
      var engine = new EngineDescriptor(UniqueId.forEngine("fake"), "fake");
      var test = child(engine, "test", null);
      var filters = everyShardOf(2);

      assertThat(filters)
          .hasSize(2)
          .allSatisfy(
              filter ->
                  assertThatThrownBy(() -> filter.apply(test))
                      .isInstanceOf(IllegalStateException.class)
                      .hasMessageContaining(test.getUniqueId().toString()));
    }
  }

  private static Set<String> discoveredTests() {
    var request =
        LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(IntegrationTestShardFilterTest.class))
            .build();
    var plan = LauncherFactory.create().discover(request);

    return plan.getRoots().stream()
        .flatMap(engine -> plan.getDescendants(engine).stream())
        .map(TestIdentifier::getUniqueId)
        .collect(Collectors.toSet());
  }

  private static List<IntegrationTestShardFilter> everyShardOf(int count) {
    return IntStream.rangeClosed(1, count)
        .mapToObj(
            shard -> {
              System.setProperty(IntegrationTestShardFilter.PROPERTY, shard + "/" + count);
              return new IntegrationTestShardFilter();
            })
        .toList();
  }

  private static List<Integer> shardsKeeping(
      List<IntegrationTestShardFilter> filters, TestDescriptor test) {
    return IntStream.rangeClosed(1, filters.size())
        .filter(shard -> filters.get(shard - 1).apply(test).included())
        .boxed()
        .toList();
  }

  private static TestDescriptor testMethodOf(String className) {
    var engine = new EngineDescriptor(UniqueId.forEngine("fake"), "fake");
    var testClass = child(engine, "class", ClassSource.from(className));
    return child(testClass, "method", MethodSource.from(className, "test"));
  }

  private static TestDescriptor child(TestDescriptor parent, String segment, TestSource source) {
    var child = new FakeDescriptor(parent.getUniqueId().append(segment, segment), source);
    parent.addChild(child);
    return child;
  }

  private static final class FakeDescriptor extends AbstractTestDescriptor {

    private FakeDescriptor(UniqueId uniqueId, TestSource source) {
      super(uniqueId, uniqueId.getLastSegment().getValue(), source);
    }

    @Override
    public Type getType() {
      return getChildren().isEmpty() ? Type.TEST : Type.CONTAINER;
    }
  }
}
