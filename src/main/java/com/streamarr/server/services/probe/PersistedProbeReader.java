package com.streamarr.server.services.probe;

import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersistedProbeReader {

  private final MediaFileContainerInfoRepository repository;

  // Repeatable read gives the outcome row and its keyframe positions one snapshot, so a
  // publication between the two reads cannot pair one outcome with another's keyframes.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<PersistedProbeOutcome> find(UUID mediaFileId) {
    return repository
        .findByMediaFileId(mediaFileId)
        .map(
            row ->
                new PersistedProbeOutcome(
                    row.getSnapshot(),
                    row.getProbeVersion(),
                    row.getOutcome(
                        () -> repository.findKeyframeKnowledge(mediaFileId).orElseThrow())))
        .filter(outcome -> outcome.isCompatibleWith(ProbeVersion.CURRENT));
  }
}
