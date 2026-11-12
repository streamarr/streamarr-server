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

  /**
   * Whether an HLS stream session copies the video in Auto without lower variants, and its copy's
   * average exceeds what the streams' share leaves beside the other counted stream sessions. Such a
   * stream session is decided again as an encode when the server creates it. False while the budget
   * is off, for a stream session on the local network, and in Original.
   */
  boolean isALoneAutoCopyOverTheShare(StreamSession session);

  /** Whether the stream session counts against the budget now, and why it does not. */
  UploadBudgetUse useOf(StreamSession session);
}
