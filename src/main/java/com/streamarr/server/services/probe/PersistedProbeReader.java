package com.streamarr.server.services.probe;

import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersistedProbeReader {

  private final MediaFileContainerInfoRepository repository;

  public Optional<PersistedProbeOutcome> find(UUID mediaFileId) {
    return repository
        .findByMediaFileId(mediaFileId)
        .map(
            row ->
                new PersistedProbeOutcome(
                    row.getSnapshot(), row.getProbeVersion(), row.getOutcome()))
        .filter(outcome -> outcome.isCompatibleWith(ProbeVersion.CURRENT));
  }
}
