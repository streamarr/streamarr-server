package com.streamarr.server.fakes;

import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.services.library.BitrateScanPriority;
import com.streamarr.server.services.library.BitrateScanRequests;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CapturingBitrateScanRequests implements BitrateScanRequests {

  /** One request with the priority it was scheduled at. */
  public record Requested(BitrateScanTaskRequest request, BitrateScanPriority priority) {}

  private final List<Requested> requests = new CopyOnWriteArrayList<>();

  @Override
  public void request(BitrateScanTaskRequest request, BitrateScanPriority priority) {
    requests.add(new Requested(request, priority));
  }

  public List<Requested> requests() {
    return List.copyOf(requests);
  }
}
