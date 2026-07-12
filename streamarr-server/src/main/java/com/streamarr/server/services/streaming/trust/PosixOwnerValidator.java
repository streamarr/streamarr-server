package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

final class PosixOwnerValidator {

  private static final long ROOT_USER_ID = 0L;
  private static final int GROUP_OR_OTHER_WRITE = 0022;
  private static final int STICKY = 01000;

  private final ProcessUserIdSource processUserIdSource;
  private volatile long resolvedProcessUserId = -1L;

  PosixOwnerValidator() {
    this(PosixOwnerValidator::currentUserId);
  }

  PosixOwnerValidator(long processUserId) {
    this(() -> processUserId);
  }

  PosixOwnerValidator(ProcessUserIdSource processUserIdSource) {
    this.processUserIdSource = processUserIdSource;
  }

  void requireCurrentOwner(Path path) {
    try {
      var ownerUserId = userId(path);
      if (ownerUserId != processUserId()) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority path must be owned by the current process user: " + path);
      }
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException
        | UnsupportedOperationException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to inspect certificate authority path ownership: " + path, e);
    }
  }

  List<Path> requireProtectedAncestors(Path protectedEntry) {
    var ancestors = new ArrayList<Path>();
    var entry = protectedEntry;
    var ancestor = entry.getParent();
    while (ancestor != null) {
      var sticky = requireProtectedDirectory(ancestor);
      if (sticky && Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
        requireTrustedEntry(entry);
      }
      ancestors.add(ancestor);
      entry = ancestor;
      ancestor = entry.getParent();
    }
    return List.copyOf(ancestors);
  }

  boolean requireProtectedDirectory(Path directory) {
    try {
      if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority protected ancestor must be a real directory: " + directory);
      }
      if (!isTrustedUser(userId(directory), processUserId())) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority protected ancestor must be owned by root or the current"
                + " process user: "
                + directory);
      }
      var mode = mode(directory);
      var sticky = (mode & STICKY) != 0;
      if ((mode & GROUP_OR_OTHER_WRITE) != 0 && !sticky) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority protected ancestor must not be group/other-writable without"
                + " the sticky bit: "
                + directory);
      }
      return sticky;
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (UnsupportedOperationException | IllegalArgumentException e) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority storage requires POSIX permissions for protected ancestors", e);
    } catch (IOException | ClassCastException | SecurityException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to secure certificate authority parent directory through protected ancestors", e);
    }
  }

  void requireTrustedEntry(Path entry) {
    try {
      if (!isTrustedUser(userId(entry), processUserId())) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority protected entry must be owned by root or the current process"
                + " user: "
                + entry);
      }
    } catch (CertificateAuthorityStoreException e) {
      throw e;
    } catch (IOException
        | UnsupportedOperationException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to inspect certificate authority protected entry: " + entry, e);
    }
  }

  private static long currentUserId() {
    Path probe = null;
    try {
      probe =
          Files.createTempFile(
              ".streamarr-owner-",
              ".tmp",
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      return userId(probe);
    } catch (IOException
        | UnsupportedOperationException
        | IllegalArgumentException
        | ClassCastException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to determine the current process user", e);
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
        } catch (IOException _) {
          // The empty owner probe contains no trust material or other application data.
        }
      }
    }
  }

  private static long userId(Path path) throws IOException {
    return ((Number) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue();
  }

  private static int mode(Path path) throws IOException {
    return ((Number) Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue();
  }

  private static boolean isTrustedUser(long userId, long processUserId) {
    return userId == ROOT_USER_ID || userId == processUserId;
  }

  private long processUserId() {
    var knownUserId = resolvedProcessUserId;
    if (knownUserId >= 0L) {
      return knownUserId;
    }
    var discoveredUserId = processUserIdSource.get();
    resolvedProcessUserId = discoveredUserId;
    return discoveredUserId;
  }

  @FunctionalInterface
  interface ProcessUserIdSource {
    long get();
  }
}
