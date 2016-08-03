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
package org.apache.brooklyn.entity.software.base;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.RecordingSensorEventListener;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.test.entity.TestApplication;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation.LocalhostMachine;
import org.apache.brooklyn.util.time.Duration;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.api.client.util.Objects;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class SameServerEntityTest {

    private LocalhostMachineProvisioningLocation loc;
    private ManagementContext mgmt;
    private TestApplication app;
    private SameServerEntity entity;
    
    RecordingSensorEventListener<Lifecycle> entityStateListener;
    RecordingSensorEventListener<Boolean> entityUpListener;
    List<RecordingSensorEventListener<Lifecycle>> childStateListeners;
    List<RecordingSensorEventListener<Boolean>> childUpListeners;

    @BeforeMethod(alwaysRun=true)
    public void setUp() {
        loc = new LocalhostMachineProvisioningLocation();
        app = TestApplication.Factory.newManagedInstanceForTests();
        mgmt = app.getManagementContext();
        entity = app.createAndManageChild(EntitySpec.create(SameServerEntity.class));
        
        entityStateListener = new RecordingSensorEventListener<>();
        entityUpListener = new RecordingSensorEventListener<>();
        app.subscriptions().subscribe(entity, Attributes.SERVICE_STATE_ACTUAL, entityStateListener);
        app.subscriptions().subscribe(entity, Attributes.SERVICE_UP, entityUpListener);
        
        childStateListeners = Lists.newArrayList();
        childUpListeners = Lists.newArrayList();
    }
    
    @AfterMethod(alwaysRun=true)
    public void tearDown() {
        if (app != null) Entities.destroyAll(mgmt);
    }
    
    @Test
    public void testUsesSameMachineLocationForEachChild() throws Exception {
        TestEntity child1 = entity.addChild(EntitySpec.create(TestEntity.class));
        TestEntity child2 = entity.addChild(EntitySpec.create(TestEntity.class));
        
        app.start(ImmutableList.of(loc));
        
        // Confirm we have the same location for everything
        Location entityLoc = Iterables.find(entity.getLocations(), Predicates.instanceOf(MachineLocation.class));
        Location child1Loc = Iterables.getOnlyElement(child1.getLocations());
        Location child2Loc = Iterables.getOnlyElement(child2.getLocations());
        
        assertSame(child1Loc, entityLoc);
        assertSame(child1Loc, child2Loc);
        assertTrue(entityLoc instanceof LocalhostMachine, "loc="+entityLoc);
        assertEquals(ImmutableSet.of(child1Loc), ImmutableSet.copyOf(loc.getInUse()));

        // Check that start was definitely called on the children
        assertEquals(child1.getCallHistory(), ImmutableList.of("start"));
        assertEquals(child2.getCallHistory(), ImmutableList.of("start"));

        // Stop the app - expect the machine to be free'ed, and the children to be stopped
        app.stop();
        
        assertEquals(ImmutableSet.of(), ImmutableSet.copyOf(loc.getInUse()));
        
        assertEquals(child1.getCallHistory(), ImmutableList.of("start", "stop"));
        assertEquals(child2.getCallHistory(), ImmutableList.of("start", "stop"));
    }
    
    // TODO Fails because we never set serviceUp=true: we never trigger the child-quorum checker,  
    // and the initial check (when the enricher is first created) does not set the value because the
    // entity is not yet managed (it happens in SameServerEntityImpl's constructor).
    @Test(enabled = false)
    public void testTransitionsToUpWithZeroChildren() throws Exception {
        runTransitionsToUp(0);
    }

    @Test
    public void testTransitionsToUpWithOneChild() throws Exception {
        runTransitionsToUp(1);
    }

    @Test
    public void testTransitionsToUpWithChildren() throws Exception {
        runTransitionsToUp(2);
    }

    @Test
    public void testTransitionsToUpWithSlowChildren() throws Exception {
        runTransitionsToUp(2, Duration.millis(5000));
    }

    protected void runTransitionsToUp(int numChildren) throws Exception {
        runTransitionsToUp(numChildren, Duration.ZERO);
    }
    
    protected void runTransitionsToUp(int numChildren, Duration delayOnStart) throws Exception {
        List<TestEntity> children = Lists.newArrayList();
        for (int i = 0; i < numChildren; i++) {
            children.add(entity.addChild(EntitySpec.create(TestEntity.class)
                    .configure(TestEntity.DELAY_ON_START, delayOnStart)));
        }
        subscribeToChildren(children);
        
        app.start(ImmutableList.of(loc));
        assertValidLifecycleStartSequence(entityStateListener.getEventValues(), entityUpListener.getEventValues());
        for (int i = 0; i < numChildren; i++) {
            assertValidLifecycleStartSequence(childStateListeners.get(i).getEventValues(), childUpListeners.get(i).getEventValues());
        }
        
        app.stop();
        assertValidLifecycleStartAndStopSequence(entityStateListener.getEventValues(), entityUpListener.getEventValues());
        for (int i = 0; i < numChildren; i++) {
            assertValidLifecycleStartAndStopSequence(childStateListeners.get(i).getEventValues(), childUpListeners.get(i).getEventValues());
        }
    }
    
    protected void subscribeToChildren(Iterable<? extends Entity> children) {
        for (Entity child : children) {
            RecordingSensorEventListener<Lifecycle> childStateListener = new RecordingSensorEventListener<>();
            RecordingSensorEventListener<Boolean> childUpListener = new RecordingSensorEventListener<>();
            app.subscriptions().subscribe(child, Attributes.SERVICE_STATE_ACTUAL, childStateListener);
            app.subscriptions().subscribe(child, Attributes.SERVICE_UP, childUpListener);
            childStateListeners.add(childStateListener);
            childUpListeners.add(childUpListener);
        }
    }

    protected void assertValidLifecycleStartSequence(Iterable<? extends Lifecycle> states, Iterable<Boolean> ups) {
        assertPermittedValues(states, ImmutableList.of(Lifecycle.CREATED, Lifecycle.STARTING, Lifecycle.RUNNING));
        assertFinalValue(states, Lifecycle.RUNNING);
        
        // We just care about the final value being true; we can transition through true-false-true 
        // while the children are starting, as long as our state is still STARTING at the time.
        assertFinalValue(ups, true);
    }
    
    protected void assertValidLifecycleStartAndStopSequence(Iterable<? extends Lifecycle> states, Iterable<Boolean> ups) {
        assertPermittedValues(states, ImmutableList.of(Lifecycle.CREATED, Lifecycle.STARTING, Lifecycle.RUNNING, Lifecycle.STOPPING, Lifecycle.STOPPED, Lifecycle.DESTROYED));
        assertFinalValue(states, Lifecycle.STOPPED);
        
        assertFinalValue(ups, false);
    }
    
    protected <T> void assertPermittedValues(Iterable<? extends T> actuals, List<? extends T> permittedInOrder) {
        int permittedIndex = 0;
        for (T actual : actuals) {
            boolean found = false;
            for (; permittedIndex < permittedInOrder.size(); permittedIndex++) {
                T permitted = permittedInOrder.get(permittedIndex);
                if (Objects.equal(actual, permitted)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                fail("actual="+actuals+"; permittedOrder="+permittedInOrder);
            }
        }
    }
    
    protected <T> void assertFinalValue(Iterable<? extends T> actuals, T expected) {
        assertEquals(Iterables.getLast(actuals), expected, "actual="+actuals+"; expectedFinal="+expected);
    }
}
