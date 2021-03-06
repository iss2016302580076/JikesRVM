/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.adaptive.recompilation.instrumentation;

import org.jikesrvm.adaptive.util.AOSOptions;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Uninterruptible;

/**
 *  Contains necessary infrastructure to perform counter-based
 *  sampling used with the instrumentation sampling code (PLDI'01)
 *  (see InstrumentationSamplingFramework)
 * */
@Uninterruptible
public final class CounterBasedSampling {
  static final boolean DEBUG = false;

  /**
   * Holds the value that is used to reset the global counter after
   * a sample is taken.
   */
  @Entrypoint
  static int resetValue = 100;

  /**
   *  The global counter.
   */
  @Entrypoint
  static int globalCounter = resetValue;

  /**
   * Initializes counter based sampling. Called when the VM boots.
   *
   * @param options the options for the AOS (and therefore also
   *  counter-based sampling)
   */
  public static void boot(AOSOptions options) {
    // Initialize the counter values
    resetValue = options.COUNTER_BASED_SAMPLE_INTERVAL - 1;
    globalCounter = resetValue;

  }
}
