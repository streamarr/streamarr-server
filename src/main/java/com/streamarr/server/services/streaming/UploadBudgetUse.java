package com.streamarr.server.services.streaming;

/** Whether a stream session counts against the upload budget, and why it does not. */
public enum UploadBudgetUse {
  /** The stream session is active, and its client reached the server over the internet. */
  COUNTED,
  /** The client attested that it reached the server over the local network. */
  LOCAL_NETWORK_ATTESTED,
  /** No ServerAdmin has set an internet upload speed, so nothing counts. */
  BUDGET_OFF,
  /** The stream session is suspended and sends nothing. */
  SUSPENDED
}
