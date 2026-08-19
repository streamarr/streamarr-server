package com.streamarr.server.services.authorization.cedar;

/** The check an intent compiles to, plus the value an allowed decision returns. */
record IntentPlan<T>(AuthorizationCheck check, T value) {}
