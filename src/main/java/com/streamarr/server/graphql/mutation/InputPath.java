package com.streamarr.server.graphql.mutation;

import java.util.ArrayList;
import java.util.List;

/** Builds {@code inputPath} values so every error names the same segments the schema does. */
public final class InputPath {

  private InputPath() {}

  public static List<String> of(String... segments) {
    return List.of(segments);
  }

  /** A list element: {@code ["members", "0", "profileId"]}. */
  public static List<String> element(String field, int index, String... rest) {
    var path = new ArrayList<String>();
    path.add(field);
    path.add(Integer.toString(index));
    path.addAll(List.of(rest));
    return List.copyOf(path);
  }
}
