package com.streamarr.server.services.serversettings;

/**
 * Every expected reason a change of the share code limit is refused; each maps to one schema error
 * type.
 */
public sealed interface ChangeShareCodeLimitRejection {

  record OutOfRange() implements ChangeShareCodeLimitRejection {}
}
