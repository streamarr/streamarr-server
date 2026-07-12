package com.streamarr.server.services.streaming.trust;

import java.nio.file.Path;

final class MacOsExtendedAclValidator {

  private final boolean macOs;
  private final MacOsAclInspection inspector;
  private final MacOsAclInspection accessGrantInspector;

  MacOsExtendedAclValidator() {
    this(System.getProperty("os.name", "").startsWith("Mac"), new MacOsAclCommandInspector());
  }

  private MacOsExtendedAclValidator(boolean macOs, MacOsAclCommandInspector inspector) {
    this(macOs, inspector::hasExtendedAcl, inspector::hasAccessGrant);
  }

  MacOsExtendedAclValidator(boolean macOs, MacOsAclInspection inspector) {
    this(macOs, inspector, inspector);
  }

  MacOsExtendedAclValidator(
      boolean macOs, MacOsAclInspection inspector, MacOsAclInspection accessGrantInspector) {
    this.macOs = macOs;
    this.inspector = inspector;
    this.accessGrantInspector = accessGrantInspector;
  }

  void requireAbsent(Path path) {
    if (!macOs) {
      return;
    }

    if (!inspector.inspect(path)) {
      return;
    }
    throw new CertificateAuthorityStoreException(
        "Certificate authority path must not have an extended ACL: " + path);
  }

  void requireNoAccessGrants(Path path) {
    if (!macOs) {
      return;
    }
    if (!accessGrantInspector.inspect(path)) {
      return;
    }
    throw new CertificateAuthorityStoreException(
        "Certificate authority protected ancestor must not grant access through an ACL: " + path);
  }

  interface MacOsAclInspection {
    boolean inspect(Path path);
  }
}
