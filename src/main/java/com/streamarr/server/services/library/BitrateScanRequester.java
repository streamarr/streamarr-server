package com.streamarr.server.services.library;

import com.streamarr.server.domain.task.BitrateScanPriority;
import com.streamarr.server.domain.task.BitrateScanTaskRequest;
import com.streamarr.server.repositories.media.BitrateScanRepository;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Requests a file's bitrate scan when its stored outcome needs one (ADR 0054). */
@Service
@Builder
@RequiredArgsConstructor
public class BitrateScanRequester {

  private final BitrateScanRepository scans;
  private final BitrateScanRequests requests;

  /** Joins the caller's transaction, so it reads the outcome that the transaction stored. */
  public void requestIfNeeded(UUID mediaFileId, String filepathUri) {
    scans
        .findBitrateScanInputs(mediaFileId)
        .ifPresent(
            inputs ->
                requests.request(
                    BitrateScanTaskRequest.builder()
                        .mediaFileId(mediaFileId)
                        .filepathUri(filepathUri)
                        .snapshot(inputs.key().snapshot())
                        .keyframePositionsDigest(inputs.key().keyframePositionsDigest())
                        .measurementVersion(inputs.key().measurementVersion())
                        .build(),
                    BitrateScanPriority.of(inputs.chosenVideoHasProbedBitrate())));
  }
}
