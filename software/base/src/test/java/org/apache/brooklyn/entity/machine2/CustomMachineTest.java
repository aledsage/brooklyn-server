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
package org.apache.brooklyn.entity.machine2;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.EnricherSpec;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.AddEffector;
import org.apache.brooklyn.core.effector.EffectorTasks.EffectorTaskFactory;
import org.apache.brooklyn.core.effector.Effectors.EffectorBuilder;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.EntityAsserts;
import org.apache.brooklyn.core.entity.RecordingSensorEventListener;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.core.sensor.function.FunctionSensor;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.enricher.stock.UpdatingMap;
import org.apache.brooklyn.location.byon.FixedListMachineProvisioningLocation;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.net.UserAndHostAndPort;
import org.apache.brooklyn.util.time.Duration;
import org.apache.brooklyn.util.time.Time;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

public class CustomMachineTest extends BrooklynAppUnitTestSupport {

    // NB: These tests don't actually require ssh to localhost -- only that 'localhost' resolves.

    private SshMachineLocation machine;
    private FixedListMachineProvisioningLocation<SshMachineLocation> loc;
    
    @BeforeMethod(alwaysRun=true)
    @Override
    public void setUp() throws Exception {
        super.setUp();
        loc = getLocation();
        DummyEffector.callCount.set(0);
    }

    @SuppressWarnings("unchecked")
    private FixedListMachineProvisioningLocation<SshMachineLocation> getLocation() {
        FixedListMachineProvisioningLocation<SshMachineLocation> loc = mgmt.getLocationManager().createLocation(LocationSpec.create(FixedListMachineProvisioningLocation.class));
        machine = mgmt.getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", "localhost"));
        loc.addMachine(machine);
        return loc;
    }

    @Test
    public void testWithProvisioningLocation() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));
        entity.start(ImmutableList.of(loc));
        
        assertEquals(entity.getAttribute(CustomMachine.HOSTNAME), machine.getAddress().getHostName());
        assertEquals(entity.getAttribute(CustomMachine.ADDRESS), machine.getAddress().getHostAddress());
        assertEquals(entity.getAttribute(Attributes.SSH_ADDRESS), UserAndHostAndPort.fromParts(machine.getUser(), machine.getAddress().getHostName(), machine.getPort()));
        assertEquals(entity.getAttribute(CustomMachine.PROVISIONING_LOCATION), loc);
        
        assertEquals(Machines.findUniqueMachineLocation(entity.getLocations()).get(), machine);
        assertEquals(loc.getInUse(), ImmutableSet.of(machine));
        
        entity.stop();
        
        assertEquals(loc.getInUse(), ImmutableSet.of());
    }
    
    @Test
    public void testWithMachine() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));
        entity.start(ImmutableList.of(machine));
        
        assertEquals(entity.getAttribute(CustomMachine.HOSTNAME), machine.getAddress().getHostName());
        assertEquals(entity.getAttribute(CustomMachine.ADDRESS), machine.getAddress().getHostAddress());
        assertEquals(entity.getAttribute(Attributes.SSH_ADDRESS), UserAndHostAndPort.fromParts(machine.getUser(), machine.getAddress().getHostName(), machine.getPort()));
        assertEquals(entity.getAttribute(CustomMachine.PROVISIONING_LOCATION), null);

        assertEquals(Machines.findUniqueMachineLocation(entity.getLocations()).get(), machine);

        entity.stop();
    }
    
    @Test
    public void testLifecycle() throws Exception {
        AttributeSensor<Boolean> reachableSensor = Sensors.newBooleanSensor("reachable");
        AtomicReference<Boolean> reachableVal = new AtomicReference<>(false);
        
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .addInitializer(sensorFeed(reachableSensor, reachableVal))
                .enricher(enricherUpdatingServiceNotUpIndicators(reachableSensor, MutableMap.of(true, null, false, "not-reachable", null, "unknown"))));
        
        // Subscribe to lifecycle events
        RecordingSensorEventListener<Boolean> serviceUpRecorder = new RecordingSensorEventListener<>(true);
        RecordingSensorEventListener<Lifecycle> serviceStateRecorder = new RecordingSensorEventListener<>(true);
        RecordingSensorEventListener<Lifecycle.Transition> serviceStateExpectedRecorder = new RecordingSensorEventListener<>(true);
        
        app.subscriptions().subscribe(entity, Attributes.SERVICE_UP, serviceUpRecorder);
        app.subscriptions().subscribe(entity, Attributes.SERVICE_STATE_ACTUAL, serviceStateRecorder);
        app.subscriptions().subscribe(entity, Attributes.SERVICE_STATE_EXPECTED, serviceStateExpectedRecorder);

        // Set reachable after delay
        new Thread(() -> {Time.sleep(20); reachableVal.set(true);}).start();
        
        // Start
        entity.start(ImmutableList.of(machine));
        
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, true);
        
        assertListsEqual(serviceUpRecorder.getEventValues(), false, ImmutableList.of(true));
        assertListsEqual(serviceStateRecorder.getEventValues(), Lifecycle.CREATED, ImmutableList.of(Lifecycle.STARTING, Lifecycle.RUNNING));
        assertListsEqual(FluentIterable.from(serviceStateExpectedRecorder.getEventValues()).transform((v) -> v.getState()).toList(), ImmutableList.of(Lifecycle.STARTING, Lifecycle.RUNNING));

        // Restart
        serviceUpRecorder.clearEvents();
        serviceStateRecorder.clearEvents();
        serviceStateExpectedRecorder.clearEvents();
        
        entity.restart();

        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, true);
        
        assertListsEqual(serviceStateRecorder.getEventValues(), ImmutableList.of(Lifecycle.STARTING, Lifecycle.RUNNING));
        assertListsEqual(FluentIterable.from(serviceStateExpectedRecorder.getEventValues()).transform((v) -> v.getState()).toList(), ImmutableList.of(Lifecycle.STARTING, Lifecycle.RUNNING));

        // Stop
        serviceUpRecorder.clearEvents();
        serviceStateRecorder.clearEvents();
        serviceStateExpectedRecorder.clearEvents();
        
        entity.stop();
        
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
        EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        
        assertListsEqual(serviceUpRecorder.getEventValues(), ImmutableList.of(false));
        assertListsEqual(serviceStateRecorder.getEventValues(), ImmutableList.of(Lifecycle.STOPPING, Lifecycle.STOPPED));
        assertListsEqual(FluentIterable.from(serviceStateExpectedRecorder.getEventValues()).transform((v) -> v.getState()).toList(), ImmutableList.of(Lifecycle.STOPPING, Lifecycle.STOPPED));
    }

    @Test
    public void testCallsDelegateStartEffectors() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_START_EFFECTOR_NAME, "myStart")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myStart")))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        entity.start(ImmutableList.of(machine));
        assertEquals(DummyEffector.callCount.get(), 1);
    }

    @Test
    public void testCallsDelegateStopEffectors() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_STOP_EFFECTOR_NAME, "myStop")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myStop")))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        entity.start(ImmutableList.of(machine));
        assertEquals(DummyEffector.callCount.get(), 0);
        
        entity.stop();
        assertEquals(DummyEffector.callCount.get(), 1);
    }

    @Test
    public void testCallsDelegateRestartEffectors() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_RESTART_EFFECTOR_NAME, "myRestart")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myRestart")))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        entity.start(ImmutableList.of(machine));
        assertEquals(DummyEffector.callCount.get(), 0);

        entity.restart();
        assertEquals(DummyEffector.callCount.get(), 1);
        DummyEffector.callCount.set(0);
    }

    @Test
    public void testStartBlocksUntilEffectorCompletes() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                    .configure(CustomMachine.DELEGATE_START_EFFECTOR_NAME, "myStart")
                    .addInitializer(new DummyEffector(ConfigBag.newInstance()
                            .configure(DummyEffector.EFFECTOR_NAME, "myStart")
                            .configure(DummyEffector.LATCH, latch)))
                    .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));
            
            // Start
            Task<Void> startTask = entity.invoke(Startable.START, ImmutableMap.of("locations", ImmutableList.of(machine)));
            
            Asserts.succeedsContinually(() -> assertFalse(startTask.isDone()));
            
            assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL), Lifecycle.STARTING);
            assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.STARTING);
            EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, true);
    
            // Let start complete
            latch.countDown();
            startTask.get(Asserts.DEFAULT_LONG_TIMEOUT);
            
            EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.RUNNING);
            EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, true);
        } finally {
            latch.countDown();
        }
    }

    @Test
    public void testStopBlocksUntilEffectorCompletes() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                    .configure(CustomMachine.DELEGATE_STOP_EFFECTOR_NAME, "myStop")
                    .addInitializer(new DummyEffector(ConfigBag.newInstance()
                            .configure(DummyEffector.EFFECTOR_NAME, "myStop")
                            .configure(DummyEffector.LATCH, latch)))
                    .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));
            
            // Start
            entity.start(ImmutableList.of(machine));
            
            // Stop
            Task<Void> stopTask = entity.invoke(Startable.STOP, ImmutableMap.of());
    
            Asserts.succeedsContinually(() -> assertFalse(stopTask.isDone()));
    
            assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL), Lifecycle.STOPPING);
            assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.STOPPING);
    
            // Let stop complete
            latch.countDown();
            stopTask.get(Asserts.DEFAULT_LONG_TIMEOUT);
            
            EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_STATE_ACTUAL, Lifecycle.STOPPED);
            EntityAsserts.assertAttributeEqualsEventually(entity, Attributes.SERVICE_UP, false);
        } finally {
            latch.countDown();
        }
    }

    @Test
    public void testDelegateStartFails() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_START_EFFECTOR_NAME, "myStart")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myStart")
                        .configure(DummyEffector.FAIL, true)))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));
        
        // Start
        try {
            entity.start(ImmutableList.of(machine));
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "invoking myStart", "Simulating failure in dummy-effector");
        }
        
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL), Lifecycle.ON_FIRE);
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.ON_FIRE);
    }

    @Test
    public void testDelegateStopFails() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_STOP_EFFECTOR_NAME, "myStop")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myStop")
                        .configure(DummyEffector.FAIL, true)))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        entity.start(ImmutableList.of(machine));

        // Stop should continue, even if effector fails (stopping process is viewed as 'inessential', 
        // behaviour copied from SoftwareProcess).
        entity.stop();
        
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL), Lifecycle.STOPPED);
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.STOPPED);
    }

    @Test
    public void testDelegateRestartFails() throws Exception {
        CustomMachine entity = app.createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_RESTART_EFFECTOR_NAME, "myRestart")
                .addInitializer(new DummyEffector(ConfigBag.newInstance()
                        .configure(DummyEffector.EFFECTOR_NAME, "myRestart")
                        .configure(DummyEffector.FAIL, true)))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        entity.start(ImmutableList.of(machine));

        // Restart
        try {
            entity.restart();
            Asserts.shouldHaveFailedPreviously();
        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "invoking myRestart", "Simulating failure in dummy-effector");
        }
        
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_ACTUAL), Lifecycle.ON_FIRE);
        assertEquals(entity.sensors().get(Attributes.SERVICE_STATE_EXPECTED).getState(), Lifecycle.ON_FIRE);
    }

    private <T> void assertListsEqual(Iterable<T> actual, List<?> expected) {
        assertEquals(MutableList.copyOf(actual), expected, "actual="+actual);
    }
    
    private <T> void assertListsEqual(Iterable<T> actual, T optionalPrefix, List<?> expected) {
        int indexToCompareFrom = Iterables.indexOf(actual, (v) -> !Objects.equal(v, optionalPrefix));
        int actualSize = Iterables.size(actual);
        List<T> actualToCompare = MutableList.copyOf(actual).subList((indexToCompareFrom < 0 ? 0 : indexToCompareFrom), actualSize);
        assertEquals(actualToCompare, expected, "actual="+actual);
    }
    
    public static class DummyEffector extends AddEffector {
        public static final ConfigKey<Duration> SLEEP_DURATION = ConfigKeys.newDurationConfigKey("sleepDuration");
        public static final ConfigKey<CountDownLatch> LATCH = ConfigKeys.newConfigKey(CountDownLatch.class, "latch");
        public static final ConfigKey<Boolean> FAIL = ConfigKeys.newBooleanConfigKey("fail");

        static final AtomicInteger callCount = new AtomicInteger();
        
        public DummyEffector(ConfigBag params) {
            super(newEffectorBuilder(params).build());
        }

        public DummyEffector(Map<String,String> params) {
            this(ConfigBag.newInstance(params));
        }

        public static EffectorBuilder<String> newEffectorBuilder(ConfigBag params) {
            return AddEffector.newEffectorBuilder(String.class, params)
                    .impl(body(params));
        }
        
        private static EffectorTaskFactory<String> body(ConfigBag params) {
            // NOTE: not nicely serializable
            return new EffectorTaskFactory<String>() {
                @Override
                public TaskAdaptable<String> newTask(Entity entity, Effector<String> effector, ConfigBag parameters) {
                    return Tasks.<String>builder().displayName("eat-sleep-rave-repeat").addAll(tasks()).build();
                }
                List<Task<Object>> tasks() {
                    callCount.incrementAndGet();
                    
                    final Boolean fail = params.get(FAIL);
                    final Duration sleepDuration = params.get(SLEEP_DURATION);
                    final CountDownLatch latch = params.get(LATCH);
                    
                    if (Boolean.TRUE.equals(fail)) {
                        throw new RuntimeException("Simulating failure in dummy-effector");
                    }
                    
                    List<Task<Object>> result = new ArrayList<>();
                    
                    if (sleepDuration != null) {
                        result.add(Tasks.builder()
                                .displayName("sleep("+sleepDuration+")")
                                .body(() -> Time.sleep(sleepDuration))
                                .build());
                    }
                    if (latch != null) {
                        result.add(Tasks.builder()
                                .displayName("latch.await("+latch+")")
                                .body(() -> { latch.await(); return null; })
                                .build());
                    }
                    return result;
                }
            };
        }
    }
    
    public static <T> EnricherSpec<?> enricherUpdatingServiceNotUpIndicators(AttributeSensor<T> sourceSensor, Map<T, ?> mapping) {
        return EnricherSpec.create(UpdatingMap.class)
                .configure(UpdatingMap.TARGET_SENSOR, Attributes.SERVICE_NOT_UP_INDICATORS)
                .configure(UpdatingMap.SOURCE_SENSOR, sourceSensor)
                .configure(UpdatingMap.COMPUTING, Functions.forMap(mapping))
                .configure(UpdatingMap.REMOVING_IF_RESULT_IS_NULL, true);
    }
    
    public static <T> FunctionSensor<?> sensorFeed(AttributeSensor<T> sensor, AtomicReference<T> val) {
        ConfigBag config = ConfigBag.newInstance()
                .configure(FunctionSensor.SENSOR_TYPE, sensor.getTypeName())
                .configure(FunctionSensor.SENSOR_NAME, sensor.getName())
                .configure(FunctionSensor.SUPPRESS_DUPLICATES, true)
                .configure(FunctionSensor.SENSOR_PERIOD, Duration.millis(10))
                .configure(FunctionSensor.FUNCTION, () -> val.get());
        
        return new FunctionSensor<Boolean>(config);
    }
}
