package com.streamarr.server.services.library.admission;

/** Which fork/join is asking for admission. */
public enum Workload {
  SCAN,
  REFRESH
}
