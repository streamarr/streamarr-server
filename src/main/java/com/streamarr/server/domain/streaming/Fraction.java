package com.streamarr.server.domain.streaming;

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
}
