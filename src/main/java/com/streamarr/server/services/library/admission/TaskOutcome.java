package com.streamarr.server.services.library.admission;

/**
 * How an admitted task ended when it did not throw. A task that throws counts as failed.
 *
 * <p>WORKED means the task reached the metadata processor (remote identification and persist);
 * SHORT_CIRCUITED means it stopped at the cheap database path (unsupported extension or an
 * already-matched file).
 */
public enum TaskOutcome {
  WORKED("worked"),
  SHORT_CIRCUITED("short_circuited");

  private final String tag;

  TaskOutcome(String tag) {
    this.tag = tag;
  }

  public String tag() {
    return tag;
  }

  public static TaskOutcome of(boolean worked) {
    return worked ? WORKED : SHORT_CIRCUITED;
  }
}
