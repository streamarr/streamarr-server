package com.streamarr.server.support;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Splits the integration test classes into CI shards by the hash code of each class name. The Java
 * SE API fixes {@link String#hashCode()}, so every JVM puts a class in the same shard, and a new
 * class gets its shard without any edit.
 */
@UtilityClass
public class IntegrationTestShards {

  /** The shard, from 1 to {@code count}, that runs the named top-level test class. */
  public int shardOf(@NonNull String testClassName, int count) {
    return Math.floorMod(testClassName.hashCode(), count) + 1;
  }
}
