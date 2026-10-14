package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.Tables.PROBE_VERSION_RESCAN;

import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JooqProbeVersionRescanRepository implements ProbeVersionRescanRepository {

  private final DSLContext dsl;

  @Override
  @Transactional
  public boolean tryMarkQueued(int probeVersion, Instant queuedOn) {
    return dsl.insertInto(PROBE_VERSION_RESCAN)
            .set(PROBE_VERSION_RESCAN.PROBE_VERSION, probeVersion)
            .set(PROBE_VERSION_RESCAN.QUEUED_ON, queuedOn.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute()
        > 0;
  }
}
