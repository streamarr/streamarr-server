package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

final class CertificateAuthorityPathResolver {

  private static final int MAXIMUM_SYMBOLIC_LINKS = 40;

  private final PosixOwnerValidator posixOwnerValidator;
  private final MacOsExtendedAclValidator macOsAclValidator;

  CertificateAuthorityPathResolver(
      PosixOwnerValidator posixOwnerValidator, MacOsExtendedAclValidator macOsAclValidator) {
    this.posixOwnerValidator = posixOwnerValidator;
    this.macOsAclValidator = macOsAclValidator;
  }

  Path resolve(Path requestedSecretPath) {
    if (requestedSecretPath == null) {
      throw new CertificateAuthorityStoreException("Certificate authority secret path is required");
    }
    requireNoParentTraversal(requestedSecretPath);
    var absolute = requestedSecretPath.toAbsolutePath().normalize();
    var requestedParent = absolute.getParent();
    if (absolute.getFileName() == null
        || requestedParent == null
        || requestedParent.getFileName() == null
        || requestedParent.getParent() == null) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority secret path requires a dedicated parent and final filename");
    }
    var resolvedContainer = resolveDirectory(requestedParent.getParent(), new HashSet<>()).path();
    return resolvedContainer.resolve(requestedParent.getFileName()).resolve(absolute.getFileName());
  }

  private ProtectedDirectory resolveDirectory(
      Path requestedDirectory, Set<Path> resolvedSymbolicLinks) {
    requireNoParentTraversal(requestedDirectory);
    var absolute = requestedDirectory.toAbsolutePath().normalize();
    var root = absolute.getRoot();
    if (root == null) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority protected ancestors require an absolute filesystem root");
    }
    var resolved = inspectDirectory(root);
    for (var component : root.relativize(absolute)) {
      var entry = resolved.path().resolve(component);
      if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority protected ancestor does not exist: " + entry);
      }
      if (resolved.sticky()) {
        posixOwnerValidator.requireTrustedEntry(entry);
      }
      resolved = resolveEntry(entry, resolved.path(), resolvedSymbolicLinks);
    }
    return resolved;
  }

  private ProtectedDirectory resolveEntry(
      Path entry, Path containingDirectory, Set<Path> resolvedSymbolicLinks) {
    if (!Files.isSymbolicLink(entry)) {
      return inspectDirectory(entry);
    }
    posixOwnerValidator.requireTrustedEntry(entry);
    if (resolvedSymbolicLinks.size() >= MAXIMUM_SYMBOLIC_LINKS
        || !resolvedSymbolicLinks.add(entry)) {
      throw new CertificateAuthorityStoreException(
          "Certificate authority protected ancestor exceeds symbolic-link depth or cycle: "
              + entry);
    }
    try {
      var target = Files.readSymbolicLink(entry);
      var resolvedTarget = target.isAbsolute() ? target : containingDirectory.resolve(target);
      return resolveDirectory(resolvedTarget, resolvedSymbolicLinks);
    } catch (IOException | SecurityException e) {
      throw new CertificateAuthorityStoreException(
          "Failed to resolve certificate authority protected ancestor: " + entry, e);
    }
  }

  private ProtectedDirectory inspectDirectory(Path directory) {
    var sticky = posixOwnerValidator.requireProtectedDirectory(directory);
    macOsAclValidator.requireNoAccessGrants(directory);
    return new ProtectedDirectory(directory, sticky);
  }

  private static void requireNoParentTraversal(Path path) {
    for (var component : path) {
      if (component.toString().equals("..")) {
        throw new CertificateAuthorityStoreException(
            "Certificate authority path must not contain parent traversal: " + path);
      }
    }
  }

  private record ProtectedDirectory(Path path, boolean sticky) {}
}
