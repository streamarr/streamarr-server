package com.streamarr.server.graphql.mutation;

/**
 * A member of a mutation's {@code userErrors} union; the concrete record name is the schema type.
 */
public interface MutationError {

  /** A complete, safe, displayable sentence — never a constraint, class, path, or identifier. */
  String message();
}
