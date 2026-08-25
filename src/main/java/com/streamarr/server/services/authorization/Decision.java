package com.streamarr.server.services.authorization;

/**
 * The outcome of evaluating an {@link Intent}. {@code Allowed} carries the intent's value; {@code
 * Denied} is a policy decision the caller turns into a typed domain rejection; {@code Failed} means
 * no decision could be made — the caller fails closed with a sanitized top-level error while the
 * diagnostics are logged and metered inside the authorization module.
 */
// java:S2326: T is the value an allowed decision carries; result-family-specific decide()
// overloads prevent callers from reading a value from the wrong family.
@SuppressWarnings("java:S2326")
public sealed interface Decision<T> {

  record Allowed<T>(T value) implements Decision<T> {}

  record Denied<T>(DenialReason reason) implements Decision<T> {}

  record Failed<T>(FailureCause cause) implements Decision<T> {}

  enum DenialReason {
    POLICY,
    REAUTHENTICATION_REQUIRED
  }

  enum FailureCause {
    /** Cedar reported a diagnostic while evaluating, even if it also reported allow. */
    EVALUATION_ERROR,
    /** The assembled entity slice did not conform to the schema. */
    INVALID_SLICE,
    /** The request (action, resource, context) did not conform to the schema. */
    INVALID_REQUEST,
    /** The engine or a fact contributor threw. */
    ENGINE_FAILURE
  }
}
