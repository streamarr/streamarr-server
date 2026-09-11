package com.streamarr.server.fakes;

import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.services.probe.ProbeRequests;
import java.util.ArrayList;
import java.util.List;

public class CapturingProbeRequests implements ProbeRequests {

  private final List<ProbeRequest> requests = new ArrayList<>();

  @Override
  public void request(ProbeRequest request) {
    requests.add(request);
  }

  public List<ProbeRequest> requests() {
    return List.copyOf(requests);
  }
}
