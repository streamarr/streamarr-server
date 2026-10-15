package com.streamarr.server.support;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.regex.Pattern;
import org.apache.commons.io.file.PathUtils;

/**
 * Counts the threads, virtual threads included, that wait for a CPU permit. A waiter parks inside
 * the permits' lock condition, which exposes no hook, so a thread dump is the only signal that it
 * waits.
 */
public final class CpuPermitWaiters {

  private static final Pattern PRIMARY_WAITER = Pattern.compile("CpuPermits\\.acquirePrimary\\(");
  private static final Pattern BACKGROUND_WAITER =
      Pattern.compile("CpuPermits\\.acquireBackground\\(");

  private CpuPermitWaiters() {}

  public static long primaryWaiters() {
    return PRIMARY_WAITER.matcher(dumpThreads()).results().count();
  }

  public static long backgroundWaiters() {
    return BACKGROUND_WAITER.matcher(dumpThreads()).results().count();
  }

  private static String dumpThreads() {
    try {
      var directory = Files.createTempDirectory("cpu-permit-waiters");
      try {
        var dump = directory.resolve("threads.json").toAbsolutePath();
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
            .dumpThreads(dump.toString(), ThreadDumpFormat.JSON);
        return Files.readString(dump);
      } finally {
        PathUtils.deleteDirectory(directory);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
