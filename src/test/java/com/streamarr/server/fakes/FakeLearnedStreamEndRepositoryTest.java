package com.streamarr.server.fakes;

import com.streamarr.server.domain.media.MediaFileContainerInfo;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.repositories.LearnedStreamEndRepositoryContract;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Learned Stream End Repository Tests")
class FakeLearnedStreamEndRepositoryTest implements LearnedStreamEndRepositoryContract {

  private final FakeMediaFileContainerInfoRepository probeOutcomes =
      new FakeMediaFileContainerInfoRepository();
  private final FakeLearnedStreamEndRepository repository =
      new FakeLearnedStreamEndRepository(probeOutcomes);

  @Override
  public LearnedStreamEndRepository repository() {
    return repository;
  }

  @Override
  public UUID createMediaFile() {
    return UUID.randomUUID();
  }

  @Override
  public void storeProbeOutcome(UUID mediaFileId, SourceFileSnapshot snapshot) {
    probeOutcomes.store(
        MediaFileContainerInfo.builder()
            .mediaFileId(mediaFileId)
            .snapshot(snapshot)
            .probeVersion(ProbeVersion.CURRENT)
            .container(ProbeContainer.builder().build())
            .build());
  }
}
