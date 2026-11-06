package com.streamarr.server.domain.streaming;

/**
 * The enhancement layer that a Dolby Vision stream's RPUs describe, as probe version 8 classifies
 * it by the RPUs' NLQ parameters (ADR 0036).
 */
public enum DolbyVisionEnhancementLayer {
  /** The probe parsed no RPU, or no parsed RPU states its own NLQ parameters. */
  UNSPECIFIED,
  /** Every parsed RPU disables the residual, as a stream without an enhancement layer does. */
  NONE,
  /** Every parsed RPU's NLQ is the identity: a minimal enhancement layer. */
  MEL,
  /** A parsed RPU's NLQ is not the identity: a full enhancement layer. */
  FEL
}
