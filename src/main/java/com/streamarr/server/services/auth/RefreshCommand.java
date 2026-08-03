package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidRefreshProposalException;
import com.streamarr.server.exceptions.InvalidRefreshTokenException;
import java.util.Base64;
import lombok.Builder;

/**
 * One refresh attempt: the credential presented, and — for bearer clients — the successor the
 * caller has already persisted. A proposal makes the rotation recoverable, because the client can
 * prove which successor it was promised without the server storing one (ADR 0020).
 */
@Builder
public record RefreshCommand(String refreshToken, String proposedSuccessor) {

  private static final int PROPOSAL_CHARACTERS = 43;
  private static final int PROPOSAL_BYTES = 32;

  public RefreshCommand {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new InvalidRefreshTokenException();
    }
    if (proposedSuccessor != null) {
      requireUsableProposal(proposedSuccessor, refreshToken);
    }
  }

  public boolean carriesProposal() {
    return proposedSuccessor != null;
  }

  private static void requireUsableProposal(String proposal, String predecessor) {
    if (proposal.equals(predecessor)) {
      throw new InvalidRefreshProposalException();
    }
    if (!isCanonicalSuccessorToken(proposal)) {
      throw new InvalidRefreshProposalException();
    }
  }

  /**
   * The proposal must be the same 256-bit shape the server generates. Length is checked before
   * decoding, and the decoded bytes are re-encoded so a non-canonical final character — which the
   * lenient decoder would silently accept — cannot produce two spellings of one token.
   */
  private static boolean isCanonicalSuccessorToken(String proposal) {
    if (proposal.length() != PROPOSAL_CHARACTERS) {
      return false;
    }

    try {
      var decoded = Base64.getUrlDecoder().decode(proposal);
      return decoded.length == PROPOSAL_BYTES
          && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(proposal);
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  public static class RefreshCommandBuilder {

    @Override
    public String toString() {
      return "RefreshCommandBuilder[refreshToken=REDACTED, proposedSuccessor=REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "RefreshCommand[refreshToken=REDACTED, proposedSuccessor=REDACTED]";
  }
}
