package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class MacOsAclCommandInspector {

  private static final List<String> SYSTEM_COMMAND = List.of("/bin/ls", "-ldeb", "--");
  private static final List<String> MODE_CHARACTERS =
      List.of("r-", "w-", "xsS-", "r-", "w-", "xsS-", "r-", "w-", "xtT-");
  private static final Set<PosixFilePermission> OWNER_ONLY_FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final List<String> commandPrefix;
  private final Duration timeout;
  private final CommandStarter commandStarter;

  MacOsAclCommandInspector() {
    this(SYSTEM_COMMAND, DEFAULT_TIMEOUT, MacOsAclCommandInspector::start);
  }

  MacOsAclCommandInspector(List<String> commandPrefix) {
    this(commandPrefix, DEFAULT_TIMEOUT, MacOsAclCommandInspector::start);
  }

  MacOsAclCommandInspector(List<String> commandPrefix, Duration timeout) {
    this(commandPrefix, timeout, MacOsAclCommandInspector::start);
  }

  MacOsAclCommandInspector(
      List<String> commandPrefix, Duration timeout, CommandStarter commandStarter) {
    this.commandPrefix = List.copyOf(commandPrefix);
    this.timeout = timeout;
    this.commandStarter = commandStarter;
  }

  boolean hasExtendedAcl(Path path) {
    return inspect(path).extendedAcl();
  }

  boolean hasAccessGrant(Path path) {
    return inspect(path).accessGrant();
  }

  private AclInspection inspect(Path path) {
    Process process = null;
    Path output = null;
    try {
      output =
          Files.createTempFile(
              ".streamarr-acl-",
              ".txt",
              PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE_PERMISSIONS));
      var command = new ArrayList<>(commandPrefix);
      command.add(path.toString());
      process = commandStarter.start(List.copyOf(command), output);
      if (!process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
        terminate(process);
        throw inspectionFailure(path);
      }
      var exitCode = process.exitValue();
      var lines = Files.readAllLines(output, StandardCharsets.UTF_8);
      if (exitCode != 0 || lines.isEmpty() || !isMetadataLine(lines.getFirst())) {
        throw inspectionFailure(path);
      }
      return classify(lines, path);
    } catch (IOException e) {
      throw inspectionFailure(path, e);
    } catch (InterruptedException e) {
      terminate(process);
      Thread.currentThread().interrupt();
      throw inspectionFailure(path, e);
    } finally {
      deleteOutput(output);
    }
  }

  private AclInspection classify(List<String> lines, Path path) {
    if (lines.size() == 1) {
      var markedAsAcl = lines.getFirst().charAt(10) == '+';
      return new AclInspection(markedAsAcl, markedAsAcl);
    }
    var accessGrant = false;
    for (var index = 1; index < lines.size(); index++) {
      accessGrant =
          switch (aclEffect(lines.get(index))) {
            case ALLOW -> true;
            case DENY -> accessGrant;
            case UNKNOWN -> throw inspectionFailure(path);
          };
    }
    return new AclInspection(true, accessGrant);
  }

  private AclEffect aclEffect(String line) {
    var entry = line.stripLeading();
    var identitySeparator = entry.indexOf(": ");
    if (identitySeparator <= 0 || !isUnsignedInteger(entry, identitySeparator)) {
      return AclEffect.UNKNOWN;
    }
    var allow = entry.lastIndexOf(" allow ");
    var deny = entry.lastIndexOf(" deny ");
    var effect = Math.max(allow, deny);
    if (effect <= identitySeparator + 2 || effect + " allow ".length() >= entry.length()) {
      return AclEffect.UNKNOWN;
    }
    return allow > deny ? AclEffect.ALLOW : AclEffect.DENY;
  }

  private boolean isUnsignedInteger(String value, int endIndex) {
    for (var index = 0; index < endIndex; index++) {
      if (!Character.isDigit(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static Process start(List<String> command, Path output) throws IOException {
    var processBuilder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    processBuilder.environment().clear();
    processBuilder.environment().put("LC_ALL", "C");
    return processBuilder.start();
  }

  private void terminate(Process process) {
    process.destroyForcibly();
  }

  private void deleteOutput(Path output) {
    if (output == null) {
      return;
    }
    try {
      Files.deleteIfExists(output);
    } catch (IOException _) {
      // Command metadata is owner-only and contains no trust material.
    }
  }

  private boolean isMetadataLine(String line) {
    if (line.length() < 12 || line.charAt(11) != ' ') {
      return false;
    }
    var type = line.charAt(0);
    if (type != '-' && type != 'd') {
      return false;
    }
    for (var index = 1; index < 10; index++) {
      if (MODE_CHARACTERS.get(index - 1).indexOf(line.charAt(index)) < 0) {
        return false;
      }
    }
    var marker = line.charAt(10);
    return marker == ' ' || marker == '+' || marker == '@';
  }

  private CertificateAuthorityStoreException inspectionFailure(Path path) {
    return new CertificateAuthorityStoreException(
        "Failed to inspect extended ACLs on certificate authority path: " + path);
  }

  private CertificateAuthorityStoreException inspectionFailure(Path path, Exception cause) {
    return new CertificateAuthorityStoreException(
        "Failed to inspect extended ACLs on certificate authority path: " + path, cause);
  }

  @FunctionalInterface
  interface CommandStarter {
    Process start(List<String> command, Path output) throws IOException;
  }

  private record AclInspection(boolean extendedAcl, boolean accessGrant) {}

  private enum AclEffect {
    ALLOW,
    DENY,
    UNKNOWN
  }
}
