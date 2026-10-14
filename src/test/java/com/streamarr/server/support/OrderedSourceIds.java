package com.streamarr.server.support;

import java.util.UUID;

/** Two new source ids that sort as {@code lower} before {@code higher}. */
public record OrderedSourceIds(String lower, String higher) {

  public static OrderedSourceIds unique() {
    var suffix = UUID.randomUUID();
    return new OrderedSourceIds("100-" + suffix, "200-" + suffix);
  }
}
