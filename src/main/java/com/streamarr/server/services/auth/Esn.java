package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.EsnRequiredException;
import com.streamarr.server.exceptions.InvalidEsnException;

/** The client-supplied hardware identity stored on pairing grants and Device registrations. */
public final class Esn {

  public static final int MAX_LENGTH = 255;

  private Esn() {}

  public static String requireValid(String rawEsn) {
    if (isMissing(rawEsn)) {
      throw new EsnRequiredException();
    }

    var esn = normalize(rawEsn);
    if (exceedsMaximum(esn)) {
      throw new InvalidEsnException(MAX_LENGTH);
    }

    return esn;
  }

  public static boolean isMissing(String rawEsn) {
    return rawEsn == null || rawEsn.isBlank();
  }

  public static String normalize(String rawEsn) {
    return rawEsn.strip();
  }

  public static boolean exceedsMaximum(String esn) {
    return esn.codePointCount(0, esn.length()) > MAX_LENGTH;
  }
}
