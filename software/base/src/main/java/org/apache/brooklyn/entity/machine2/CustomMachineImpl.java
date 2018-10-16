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

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;
import org.apache.brooklyn.location.jclouds.JcloudsLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

public class CustomMachineImpl extends AbstractEntity implements CustomMachine {

    // This code is copied from org.apache.brooklyn.entity.software.base.SoftwareProcessImpl, and then
    // massively cut-down to remove much of the functionality. For example, we do not assume that it
    // is ssh'able.
    // 
    // It has the following additions:
    //  - Avoid waiting for ssh/winrm by setting defaults for onbox.base.dir.skipResolution, waitForSshable, 
    //    waitForWinRmAvailable and pollForFirstReachableAddress.
    //  - Delegates to the given effector(s) for start, stop and restart (if configured).
    
    private static final Logger LOG = LoggerFactory.getLogger(CustomMachineImpl.class);

    private transient CustomMachineDriver driver;

    @Override
    public void init() {
        super.init();
        getLifecycleEffectorTasks().attachLifecycleEffectors(this);
    }
    
    @Override 
    public void onManagementStarting() {
        super.onManagementStarting();
        
        Lifecycle state = getAttribute(SERVICE_STATE_ACTUAL);
        if (state == null || state == Lifecycle.CREATED) {
            // Expect this is a normal start() sequence (i.e. start() will subsequently be called)
            sensors().set(SERVICE_UP, false);
            ServiceStateLogic.setExpectedState(this, Lifecycle.CREATED);
            // force actual to be created because this is expected subsequently
            sensors().set(SERVICE_STATE_ACTUAL, Lifecycle.CREATED);
        }
    }
    
    @Override
    public void rebind() {
        //SERVICE_STATE_ACTUAL might be ON_FIRE due to a temporary condition (problems map non-empty)
        //Only if the expected state is ON_FIRE then the entity has permanently failed.
        Lifecycle expectedState = ServiceStateLogic.getExpectedState(this);
        if (expectedState == null || expectedState != Lifecycle.RUNNING) {
            LOG.warn("On rebind of {}, not calling software process rebind hooks because expected state is {}", this, expectedState);
            return;
        }

        Lifecycle actualState = ServiceStateLogic.getActualState(this);
        if (actualState == null || actualState != Lifecycle.RUNNING) {
            LOG.warn("Rebinding entity {}, even though actual state is {}. Expected state is {}", new Object[] { this, actualState, expectedState });
        }

        // e.g. rebinding to a running instance
        // FIXME What if location not set?
        LOG.info("Rebind {} connecting to pre-running service", this);
        
        MachineLocation machine = getMachineOrNull();
        if (machine != null) {
            initDriver(machine);
            driver.rebind();
            LOG.debug("On rebind of {}, re-created driver {}", this, driver);
        } else {
            LOG.info("On rebind of {}, no MachineLocation found (with locations {}) so not generating driver", this, getLocations());
        }
    }
    
    public void waitForServiceUp() {
        Duration timeout = getConfig(BrooklynConfigKeys.START_TIMEOUT);
        waitForServiceUp(timeout);
    }
    
    public void waitForServiceUp(Duration duration) {
        Entities.waitForServiceUp(this, duration);
    }
    
    protected Map<String,Object> obtainProvisioningFlags(MachineProvisioningLocation<?> location) {
        ConfigBag result = ConfigBag.newInstance(location.getProvisioningFlags(ImmutableList.of(getClass().getName())));

        // copy provisioning properties raw in case they contain deferred values
        // normal case is to have provisioning.properties.xxx in the map, so this is how we get it
        Map<String, Object> raw1 = PROVISIONING_PROPERTIES.rawValue(config().getBag().getAllConfigRaw());
        // do this also, just in case a map is stored at the key itself (not sure this is needed, raw1 may include it already)
        Maybe<Object> raw2 = config().getRaw(PROVISIONING_PROPERTIES);
        if (raw2.isPresentAndNonNull()) {
            Object pp = raw2.get();
            if (!(pp instanceof Map)) {
                LOG.debug("When obtaining provisioning properties for "+this+" to deploy to "+location+", detected that coercion was needed, so coercing sooner than we would otherwise");
                pp = config().get(PROVISIONING_PROPERTIES);
            }
            result.putAll((Map<?,?>)pp);
        }
        // finally write raw1 on top
        result.putAll(raw1);

        result.putIfAbsent(JcloudsLocation.WAIT_FOR_SSHABLE, "false");
        result.putIfAbsent(JcloudsLocation.WAIT_FOR_WINRM_AVAILABLE, "false");
        result.putIfAbsent(JcloudsLocation.POLL_FOR_FIRST_REACHABLE_ADDRESS, "false");
        result.put(LocationConfigKeys.CALLER_CONTEXT, this);
        
        return result.getAllConfigMutable();
    }

    protected void initDriver(MachineLocation machine) {
        if (driver == null) {
            driver = new CustomMachineDriver(this, machine);
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding {@link #preStart()} or {@link #postStart()})}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void start(final Collection<? extends Location> locations) {
        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().start(locations);
        } else {
            Task<?> task = Tasks.builder().displayName("start (sequential)").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().start(locations); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding  {@link #preStop()} or {@link #postStop()}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void stop() {
        // TODO There is a race where we set SERVICE_UP=false while sensor-adapter threads may still be polling.
        // The other thread might reset SERVICE_UP to true immediately after we set it to false here.
        // Deactivating adapters before setting SERVICE_UP reduces the race, and it is reduced further by setting
        // SERVICE_UP to false at the end of stop as well.
        
        // Perhaps we should wait until all feeds have completed here, 
        // or do a SERVICE_STATE check before setting SERVICE_UP to true in a feed (?).

        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().stop(ConfigBag.EMPTY);
        } else {
            Task<?> task = Tasks.builder().displayName("stop").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().stop(ConfigBag.EMPTY); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }

    /**
     * If custom behaviour is required by sub-classes, consider overriding {@link #preRestart()} or {@link #postRestart()}.
     * Also consider adding additional work via tasks, executed using {@link DynamicTasks#queue(String, Callable)}.
     */
    @Override
    public final void restart() {
        if (DynamicTasks.getTaskQueuingContext() != null) {
            getLifecycleEffectorTasks().restart(ConfigBag.EMPTY);
        } else {
            Task<?> task = Tasks.builder().displayName("restart").body(new Runnable() {
                @Override public void run() { getLifecycleEffectorTasks().restart(ConfigBag.EMPTY); }
            }).build();
            Entities.submit(this, task).getUnchecked();
        }
    }
    
    protected CustomMachineDriverLifecycleEffectorTasks getLifecycleEffectorTasks() {
        return getConfig(LIFECYCLE_EFFECTOR_TASKS);
    }

    @Override
    public SoftwareProcessDriver getDriver() {
        return driver;
    }
    
    protected MachineLocation getMachineOrNull() {
        return Iterables.get(Iterables.filter(getLocations(), MachineLocation.class), 0, null);
    }
}
