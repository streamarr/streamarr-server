package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamSession;
import java.util.Set;

/** What the server's upload budget leaves out of a stream session's multivariant playlist. */
public interface UploadBudget {

  /**
   * The labels of the variants that the budget leaves out of the multivariant playlist that the
   * server serves now. Empty while the budget is off. Never every listed variant: the lowest stays.
   * When it leaves out the first-listed variant, the top variant that stays starts first (ADR
   * 0050).
   */
  Set<String> withholdFromPlaylist(StreamSession session);

  /**
   * The variant that the stream session's multivariant playlist would list first now, without
   * serving it: the first-listed variant, or the top variant that stays when the budget leaves the
   * first-listed variant out.
   */
  String topKeptVariant(StreamSession session);

  /** Whether the stream session counts against the budget now, and why it does not. */
  UploadBudgetUse useOf(StreamSession session);
}
