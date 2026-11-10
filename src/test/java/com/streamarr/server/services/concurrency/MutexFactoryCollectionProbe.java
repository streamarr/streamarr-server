package com.streamarr.server.services.concurrency;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

/**
 * Reports what {@link MutexFactory} keeps for a key once the collector has cleared soft references.
 *
 * <p>{@code MutexFactoryCollectionTest} runs it in a JVM of its own, so its collections and the
 * collector settings it needs stay out of the JVM that runs the other tests.
 */
public final class MutexFactoryCollectionProbe {

  private static final String UNREFERENCED_KEY = "unreferenced";
  private static final int MAX_COLLECTIONS = 10;

  private MutexFactoryCollectionProbe() {}

  public static void main(String[] args) {
    var mutexFactory = new MutexFactory<String>();
    var unreferenced = new WeakReference<>(mutexFactory.getMutex(UNREFERENCED_KEY));
    // Created after the mutex, so it is cleared no earlier than a soft reference to it.
    var softlyReferenced = new SoftReference<>(new Object());

    collectUntilCleared(softlyReferenced);

    report("softReferencesCleared", softlyReferenced.refersTo(null));
    report("unreferencedMutexCollected", unreferenced.refersTo(null));
  }

  // With SoftRefLRUPolicyMSPerMB=0 the collector clears a soft reference at the second collection
  // after its last use: the first only ages it. refersTo reads the reference without counting as a
  // use.
  private static void collectUntilCleared(Reference<?> reference) {
    for (var collection = 0; collection < MAX_COLLECTIONS; collection++) {
      if (reference.refersTo(null)) {
        return;
      }

      System.gc();
    }
  }

  private static void report(String observation, boolean value) {
    System.out.println(observation + "=" + value);
  }
}
