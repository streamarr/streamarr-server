package com.streamarr.server.domain.auth;

import java.util.UUID;

/**
 * The reserved identity recorded for work that no user requested, such as a scan, a refresh, the
 * file watcher or a scheduled task.
 *
 * <p>The System actor is not an account. No {@code user_account} row can take its id, so it has no
 * credentials, sessions or tokens, cannot sign in, holds no Household or Profile, and never appears
 * in account lists. A request made without a signed-in account never acts as the System actor.
 */
public final class SystemActor {

  /** The System actor's id. Audit columns and requesters name the System actor by this id. */
  public static final UUID ID = UUID.fromString("b634c755-3c6a-4ff1-a0a7-3e812d0ed1b9");

  private SystemActor() {}
}
