package com.streamarr.server.services.probe;

import com.streamarr.server.domain.media.PersistedProbeOutcome;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ChapterKnowledge;
import com.streamarr.server.exceptions.MalformedKeyframePositionsException;
import com.streamarr.server.repositories.media.MediaFileContainerInfoRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
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
    try {
      return repository
          .findByMediaFileId(mediaFileId)
          .map(
              row ->
                  new PersistedProbeOutcome(
                      row.getSnapshot(),
                      row.getProbeVersion(),
                      row.getOutcome(
                          () -> repository.findKeyframeKnowledge(mediaFileId).orElseThrow(),
                          () -> chapterKnowledge(mediaFileId))))
          .filter(outcome -> outcome.isCompatibleWith(ProbeVersion.CURRENT));
    } catch (MalformedKeyframePositionsException _) {
      // Without an outcome, the scheduler requests a probe, and its publication replaces the row.
      return Optional.empty();
    }
  }

  /**
   * Reads what the stored outcome of each listed file says about its container's chapters. A file
   * without a stored outcome reads as {@link ChapterKnowledge.NotReported}.
   */
  @Transactional(readOnly = true)
  public Map<UUID, ChapterKnowledge> findChapters(@NonNull Collection<UUID> mediaFileIds) {
    var stored = repository.findChapterKnowledge(mediaFileIds);
    var chapters = new HashMap<UUID, ChapterKnowledge>();
    mediaFileIds.forEach(mediaFileId -> chapters.put(mediaFileId, chaptersOf(stored, mediaFileId)));
    return chapters;
  }

  private ChapterKnowledge chapterKnowledge(UUID mediaFileId) {
    return chaptersOf(repository.findChapterKnowledge(List.of(mediaFileId)), mediaFileId);
  }

  private static ChapterKnowledge chaptersOf(Map<UUID, ChapterKnowledge> stored, UUID mediaFileId) {
    return stored.getOrDefault(mediaFileId, new ChapterKnowledge.NotReported());
  }
}
