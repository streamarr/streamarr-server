package com.streamarr.server.services.serversettings;

/** Every expected reason {@code changeUploadBudget} refuses; each maps to one schema error type. */
public sealed interface ChangeUploadBudgetRejection {

  record OutOfRange() implements ChangeUploadBudgetRejection {}
}
