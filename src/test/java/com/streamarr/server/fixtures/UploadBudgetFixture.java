package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.services.streaming.UploadBudget;
import com.streamarr.server.services.streaming.UploadBudgetUse;
import java.util.Set;

public final class UploadBudgetFixture {

  /** The default: no upload budget bounds a playlist, and no stream session counts. */
  public static final UploadBudget OFF =
      new UploadBudget() {
        @Override
        public Set<String> withholdFromPlaylist(StreamSession session) {
          return Set.of();
        }

        @Override
        public String topKeptVariant(StreamSession session) {
          return session.firstListedVariant();
        }

        @Override
        public UploadBudgetUse useOf(StreamSession session) {
          return UploadBudgetUse.BUDGET_OFF;
        }
      };

  private UploadBudgetFixture() {}
}
