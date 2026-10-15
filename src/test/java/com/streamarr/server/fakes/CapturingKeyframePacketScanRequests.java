package com.streamarr.server.fakes;

import com.streamarr.server.domain.task.KeyframePacketScanTaskRequest;
import com.streamarr.server.services.library.KeyframePacketScanRequests;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CapturingKeyframePacketScanRequests implements KeyframePacketScanRequests {

  private final List<KeyframePacketScanTaskRequest> requests = new CopyOnWriteArrayList<>();

  @Override
  public void request(KeyframePacketScanTaskRequest request) {
    requests.add(request);
  }

  public List<KeyframePacketScanTaskRequest> requests() {
    return List.copyOf(requests);
  }
}
