package com.streamarr.server.fakes;

import com.streamarr.server.repositories.LearnedStreamEndRepositoryContract;
import com.streamarr.server.repositories.media.LearnedStreamEndRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

@Tag("UnitTest")
@DisplayName("Fake Learned Stream End Repository Tests")
class FakeLearnedStreamEndRepositoryTest implements LearnedStreamEndRepositoryContract {

  private final FakeLearnedStreamEndRepository repository = new FakeLearnedStreamEndRepository();

  @Override
  public LearnedStreamEndRepository repository() {
    return repository;
  }

  @Override
  public UUID createMediaFile() {
    return UUID.randomUUID();
  }
}
