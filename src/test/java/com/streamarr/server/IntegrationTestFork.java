package com.streamarr.server;

import java.util.Properties;
import lombok.experimental.UtilityClass;

/** The Failsafe fork that runs the integration tests, and the database that fork uses. */
@UtilityClass
public class IntegrationTestFork {

  /** The system property in which Failsafe names the fork that runs the integration tests. */
  public static final String NUMBER_PROPERTY = "streamarr.test.fork-number";

  // With reuse enabled, every JVM that asks for the same container settings gets one container,
  // so two forks with one database name would truncate each other's tables. Fork 1, and a run
  // outside Failsafe's forks, keep the container that developers already reuse.
  static String databaseName(Properties systemProperties) {
    var fork = Integer.parseInt(systemProperties.getProperty(NUMBER_PROPERTY, "1"));
    if (fork == 1) {
      return "streamarr";
    }

    return "streamarr_fork" + fork;
  }
}
