package com.streamarr.server.fixtures;

import com.streamarr.server.fakes.CapturingBitrateScanRequests;
import com.streamarr.server.fakes.FakeBitrateScanRepository;
import com.streamarr.server.services.library.BitrateScanRequester;

public final class BitrateScanRequesters {

  private BitrateScanRequesters() {}

  /** A requester for tests that publish outcomes and leave bitrate scans out of view. */
  public static BitrateScanRequester none() {
    return BitrateScanRequester.builder()
        .scans(new FakeBitrateScanRepository())
        .requests(new CapturingBitrateScanRequests())
        .build();
  }
}
