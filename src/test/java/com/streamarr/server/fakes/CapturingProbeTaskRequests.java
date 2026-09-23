package com.streamarr.server.fakes;

import com.streamarr.server.domain.task.ProbeTaskRequest;
import com.streamarr.server.services.probe.ProbeTaskRequests;
import java.util.ArrayList;
import java.util.List;

public class CapturingProbeTaskRequests implements ProbeTaskRequests {

  private final List<ProbeTaskRequest> requests = new ArrayList<>();
  private final List<ProbeTaskRequest> retryingRequests = new ArrayList<>();

  @Override
  public void request(ProbeTaskRequest request) {
    requests.add(request);
  }

  @Override
  public void requestRetryingFailure(ProbeTaskRequest request) {
    retryingRequests.add(request);
  }

  public List<ProbeTaskRequest> retryingRequests() {
    return List.copyOf(retryingRequests);
  }

  public List<ProbeTaskRequest> requests() {
    return List.copyOf(requests);
  }
}
