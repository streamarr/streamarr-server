package com.streamarr.server.services.streaming.remote;

import java.net.URI;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class WorkerSpiffeIdentityMapper {

  private static final Integer URI_SUBJECT_ALTERNATIVE_NAME = 6;

  private final String workerIdentityPrefix;

  public WorkerSpiffeIdentityMapper(String trustDomain) {
    workerIdentityPrefix = workerIdentityPrefix(trustDomain);
  }

  public UUID workerId(X509Certificate certificate) {
    Objects.requireNonNull(certificate);
    var uriNames = uriSubjectAlternativeNames(certificate);
    if (uriNames.size() != 1) {
      throw new WorkerIdentityException();
    }

    var identity = uriNames.getFirst();
    if (!identity.startsWith(workerIdentityPrefix)) {
      throw new WorkerIdentityException();
    }

    var workerId = parseWorkerId(identity.substring(workerIdentityPrefix.length()));
    if (!identity.equals(workerIdentityPrefix + workerId)) {
      throw new WorkerIdentityException();
    }

    return workerId;
  }

  private List<String> uriSubjectAlternativeNames(X509Certificate certificate) {
    try {
      var subjectAlternativeNames = certificate.getSubjectAlternativeNames();
      if (subjectAlternativeNames == null) {
        throw new WorkerIdentityException();
      }

      var uriNames = new ArrayList<String>();
      for (var name : subjectAlternativeNames) {
        if (name.isEmpty()) {
          throw new WorkerIdentityException();
        }
        if (!URI_SUBJECT_ALTERNATIVE_NAME.equals(name.getFirst())) {
          continue;
        }
        if (name.size() < 2 || !(name.get(1) instanceof String uriName)) {
          throw new WorkerIdentityException();
        }
        uriNames.add(uriName);
      }
      return List.copyOf(uriNames);
    } catch (CertificateParsingException _) {
      throw new WorkerIdentityException();
    }
  }

  private static UUID parseWorkerId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException _) {
      throw new WorkerIdentityException();
    }
  }

  private static String workerIdentityPrefix(String trustDomain) {
    if (trustDomain == null || trustDomain.isBlank()) {
      throw new IllegalArgumentException("Worker trust domain is required");
    }

    URI trustDomainUri;
    try {
      trustDomainUri = URI.create("spiffe://" + trustDomain);
    } catch (IllegalArgumentException _) {
      throw new IllegalArgumentException("Worker trust domain is invalid");
    }
    if (!trustDomain.equals(trustDomainUri.getRawAuthority()) || trustDomainUri.getPort() != -1) {
      throw new IllegalArgumentException("Worker trust domain is invalid");
    }

    return trustDomainUri + "/streamarr/worker/";
  }
}
