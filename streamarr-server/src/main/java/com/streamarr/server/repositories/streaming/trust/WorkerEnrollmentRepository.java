package com.streamarr.server.repositories.streaming.trust;

import com.streamarr.server.services.streaming.trust.EnrollmentGrantRequest;
import com.streamarr.server.services.streaming.trust.GrantCreationResult;

public interface WorkerEnrollmentRepository {

  GrantCreationResult createGrant(EnrollmentGrantRequest request);
}
