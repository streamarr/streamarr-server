package com.streamarr.server.domain.streaming;

import java.math.BigInteger;

/** An exact fraction whose terms are both positive, such as 24000/1001 frames per second. */
public record Fraction(int numerator, int denominator) {

  public Fraction {
    if (numerator <= 0 || denominator <= 0) {
      throw new IllegalArgumentException(
          "A fraction needs positive terms, not " + numerator + "/" + denominator);
    }
  }

  public double toDouble() {
    return (double) numerator / denominator;
  }

  /** This fraction divided by a whole number, in lowest terms. */
  public Fraction dividedBy(int divisor) {
    var dividedDenominator = Math.multiplyExact(denominator, divisor);
    var commonFactor =
        BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(dividedDenominator)).intValueExact();
    return new Fraction(numerator / commonFactor, dividedDenominator / commonFactor);
  }
}
