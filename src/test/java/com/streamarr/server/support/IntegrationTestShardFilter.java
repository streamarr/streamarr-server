package com.streamarr.server.support;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/**
 * Keeps only the tests of the CI shard that the {@value #PROPERTY} system property selects, written
 * as shard and count, such as {@code 1/2}. Without the property it keeps every test. JUnit loads it
 * from {@code META-INF/services} for every test run.
 */
public final class IntegrationTestShardFilter implements PostDiscoveryFilter {

  public static final String PROPERTY = "streamarr.test.shard";

  private static final Pattern SHARD_OF_COUNT = Pattern.compile("([1-9]\\d{0,2})/([1-9]\\d{0,2})");

  private final Optional<SelectedShard> selected;

  public IntegrationTestShardFilter() {
    selected =
        Optional.ofNullable(System.getProperty(PROPERTY)).map(IntegrationTestShardFilter::select);
  }

  // JUnit applies the filter to every descriptor but removes only excluded leaves below an engine.
  @Override
  public FilterResult apply(TestDescriptor descriptor) {
    if (descriptor.isRoot()) {
      return FilterResult.included("every shard runs the test engines");
    }

    return selected
        .map(shard -> shard.filter(testClassName(descriptor)))
        .orElseGet(() -> FilterResult.included("no shard selected"));
  }

  private static SelectedShard select(String value) {
    var matcher = SHARD_OF_COUNT.matcher(value);
    if (!matcher.matches()
        || Integer.parseInt(matcher.group(1)) > Integer.parseInt(matcher.group(2))) {
      throw new IllegalArgumentException(
          PROPERTY + " must name a shard of a count, such as 1/2, but was \"" + value + "\"");
    }

    return new SelectedShard(
        Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
  }

  // The outermost class, so that a nested class's tests run in its enclosing class's shard.
  private static String testClassName(TestDescriptor descriptor) {
    return Stream.iterate(descriptor, Objects::nonNull, current -> current.getParent().orElse(null))
        .flatMap(current -> current.getSource().stream())
        .filter(ClassSource.class::isInstance)
        .map(ClassSource.class::cast)
        .map(ClassSource::getClassName)
        .reduce((inner, outer) -> outer)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    descriptor.getUniqueId() + " belongs to no test class, so it has no shard"));
  }

  private record SelectedShard(int shard, int count) {

    FilterResult filter(String testClassName) {
      return FilterResult.includedIf(
          IntegrationTestShards.shardOf(testClassName, count) == shard,
          () -> testClassName + " runs in shard " + shard,
          () -> testClassName + " runs in another shard");
    }
  }
}
