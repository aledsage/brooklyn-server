/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.brooklyn.util.repeat;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Callables;

public class RepeaterIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(RepeaterIntegrationTest.class);

    private static final long EARLY_RETURN_GRACE = 10;
    private static final long OVERHEAD_GRACE = 50;

    /**
     * Check that the {@link Repeater} will stop after a time limit.
     *
     * The repeater is configured to run every 100ms and never stop until the limit is reached.
     * This is given as {@link Repeater#limitTimeTo(org.apache.brooklyn.util.time.Duration)} and the execution time
     * is then checked to ensure it is between 100% and 400% of the specified value. Due to scheduling
     * delays and other factors in a non RTOS system it is expected that the repeater will take much
     * longer to exit occasionally.
     *
     * @see #runRespectsMaximumIterationLimitAndReturnsFalseIfReached()
     */
    @Test(groups="Integration")
    public void testRunRespectsTimeLimitAndReturnsFalseIfReached() {
        final long LIMIT = 2000l;
        Repeater repeater = new Repeater("runRespectsTimeLimitAndReturnsFalseIfReached")
            .every(Duration.millis(100))
            .until(Callables.returning(false))
            .limitTimeTo(LIMIT, TimeUnit.MILLISECONDS);

        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean result = repeater.run();
        stopwatch.stop();

        assertFalse(result);

        long difference = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        assertTrue(difference >= LIMIT, "Difference was: " + difference);
        assertTrue(difference < 4 * LIMIT, "Difference was: " + difference);
    }
    
    /**
     * Checks that each run backs off exponentially.
     */
    @Test(groups="Integration")
    public void testRunBacksOff() {
        // warmup to ensure classloaded etc (important if care about timings)
        runWithBackOff(2, Duration.millis(1), 1.0, Duration.millis(1), null);
        
        // Iterations limited to 6, so expect 5 backoffs.
        final List<Long> timestamps = runWithBackOff(6, Duration.millis(100), 2.0, Duration.millis(1000), null);
        List<Long> expectedBackoffs = ImmutableList.of(100L, 200L, 400L, 800L, 1000L);
        
        for (int i = 1; i < 6; i++) {
            long actualBackoff = timestamps.get(i) - timestamps.get(i-1);
            long expectedBackoff = expectedBackoffs.get(i-1);
            assertOrdered("i="+i+", timestamps="+timestamps, expectedBackoff - EARLY_RETURN_GRACE, actualBackoff, expectedBackoff + OVERHEAD_GRACE);
        }
    }
    
    /**
     * Checks that each exponential backoff has the jitter applied to it.
     */
    @Test(groups="Integration")
    public void testBacksOffWithJitter() {
        // A dummy jitterer that just halfs the backoff!
        // See JittererTest for tests of real jitter generators.
        Jitterer jitterer = new Jitterer() {
            @Override public Duration jitter(int iteration, Duration backoff) {
                return Duration.nanos(backoff.nanos() / 2);
            }
            @Override
            public String toString() {
                return "exactlyHalf()";
            }
        };
        
        // warmup to ensure classloaded etc (important if care about timings)
        runWithBackOff(2, Duration.millis(1), 1.0, Duration.millis(1), jitterer);
        
        // Iterations limited to 6, so expect 5 backoffs.
        // Last backoff is the jitter of 1000ms.
        final List<Long> timestamps = runWithBackOff(6, Duration.millis(100), 2.0, Duration.millis(1000), jitterer);
        List<Long> expectedBackoffs = ImmutableList.of(50L, 100L, 200L, 400L, 500L);
        
        for (int i = 1; i < 6; i++) {
            long actualBackoff = timestamps.get(i) - timestamps.get(i-1);
            long expectedBackoff = expectedBackoffs.get(i-1);
            assertOrdered("i="+i+", timestamps="+timestamps, expectedBackoff - EARLY_RETURN_GRACE, actualBackoff, expectedBackoff + OVERHEAD_GRACE);
        }
    }

    private List<Long> runWithBackOff(int limitIterations, Duration initialDelay, double multiplier, Duration finalDelay, Jitterer jitterer) {
        final List<Long> timestamps = Collections.synchronizedList(new ArrayList<>());
        Stopwatch stopwatch = Stopwatch.createStarted();
        
        boolean result = new Repeater("runRespectsTimeLimitAndReturnsFalseIfReached")
                .backoff(initialDelay, multiplier, finalDelay, jitterer)
                .until(() -> { timestamps.add(stopwatch.elapsed(TimeUnit.MILLISECONDS)); return false; })
                .limitIterationsTo(limitIterations)
                .run();
        
        assertFalse(result);
        assertEquals(timestamps.size(), limitIterations);
        
        LOG.info("initialDelay="+initialDelay+"; multiplier="+multiplier+"; finalDelay="+finalDelay+"; jitterer="+jitterer+"; timestamps="+timestamps);
        return timestamps;
    }

    private void assertOrdered(String context, long... vals) {
        String errMsg = "context=["+context+"], vals="+Arrays.toString(vals);
        Long previous = null;
        for (long val : vals) {
            if (previous != null) {
                assertTrue(val >= previous, errMsg);
            }
            previous = val;
        }
    }
}
