package com.streamarr.server.services.serversettings;

/**
 * Every expected reason a change of the share code lifetime or the invitation lifetime is refused;
 * each maps to one schema error type.
 */
public sealed interface ChangeLifetimeRejection {

  record OutOfRange() implements ChangeLifetimeRejection {}
}
