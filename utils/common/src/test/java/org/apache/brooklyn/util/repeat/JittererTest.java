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
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.brooklyn.util.time.Duration;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.Range;

public class JittererTest {

    @Test
    public void testNone() {
        assertEquals(Jitterer.NONE.jitter(1, Duration.ONE_SECOND), Duration.ZERO);
    }
    
    @Test
    public void testFullJitter() {
        List<Duration> results = runJitter(10, Jitterer.FULL_JITTER, Duration.ONE_SECOND);
        assertNotAllSame(results);
        assertAllResults(results, Range.closed(Duration.millis(0), Duration.millis(1000)));
    }
    
    @Test
    public void testPlusFraction() {
        Jitterer.PlusFraction jitterer = new Jitterer.PlusFraction(0.1);
        List<Duration> results = runJitter(10, jitterer, Duration.ONE_SECOND);
        assertNotAllSame(results);
        assertAllResults(results, Range.closed(Duration.millis(1000), Duration.millis(1100)));
    }
    
    @Test
    public void testMinusFraction() {
        Jitterer.MinusFraction jitterer = new Jitterer.MinusFraction(0.1);
        List<Duration> results = runJitter(10, jitterer, Duration.ONE_SECOND);
        assertNotAllSame(results);
        assertAllResults(results, Range.closed(Duration.millis(900), Duration.millis(1000)));
    }
    
    @Test
    public void testPlusOrMinusFraction() {
        Jitterer.PlusOrMinusFraction jitterer = new Jitterer.PlusOrMinusFraction(0.1);
        List<Duration> results = runJitter(100, jitterer, Duration.ONE_SECOND);
        assertAllResults(results, Range.closed(Duration.millis(900), Duration.millis(1100)));
        assertAtLeatOneResult(results, Range.closed(Duration.millis(900), Duration.millis(999)));
        assertAtLeatOneResult(results, Range.closed(Duration.millis(1001), Duration.millis(1100)));
    }
    
    private List<Duration> runJitter(int numTimes, Jitterer jitterer, Duration backoff) {
        List<Duration> results = new ArrayList<>();
        for (int i = 0; i < numTimes; i++) {
            results.add(jitterer.jitter(1, backoff));
        }
        return results;
    }
    
    private void assertAllResults(List<Duration> results, Predicate<? super Duration> condition) {
        String errMsg = "results="+results;
        for (Duration result : results) {
            assertTrue(condition.apply(result), errMsg);
        }
    }
    
    private void assertAtLeatOneResult(List<Duration> results, Predicate<? super Duration> condition) {
        for (Duration result : results) {
            if (condition.apply(result)) {
                return;
            }
        }
        fail("results="+results);
    }
    
    private void assertNotAllSame(List<Duration> results) {
        assertTrue(new HashSet<>(results).size() > 1, "results="+results);
    }
}
