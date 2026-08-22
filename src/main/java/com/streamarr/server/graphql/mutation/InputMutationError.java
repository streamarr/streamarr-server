package com.streamarr.server.graphql.mutation;

import java.util.List;

/** A rejection attributable to one field of the mutation's {@code input} object. */
public interface InputMutationError extends MutationError {

  /** Segments relative to {@code input}; list indexes are decimal strings. */
  List<String> inputPath();
}
