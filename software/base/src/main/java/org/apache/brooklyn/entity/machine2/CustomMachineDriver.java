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

import static com.google.common.base.Preconditions.checkNotNull;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;
import org.apache.brooklyn.entity.software.base.lifecycle.MachineLifecycleEffectorTasks;
import org.apache.brooklyn.entity.software.base.lifecycle.MachineLifecycleEffectorTasks.CloseableLatch;
import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

/**
 * An implementation of the {@link SoftwareProcessDriver} for {@link CustomMachine}. It delegates
 * for start(), stop() and restart() by calling the given effectors (if configured).
 */
public class CustomMachineDriver implements SoftwareProcessDriver {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(CustomMachineDriver.class);

    protected final CustomMachine entity;
    protected final ResourceUtils resource;
    protected final Location location;

    public CustomMachineDriver(CustomMachine entity, Location location) {
        this.entity = checkNotNull(entity, "entity");
        this.location = checkNotNull(location, "location");
        this.resource = ResourceUtils.create(entity);
    }

    @Override
    public void rebind() {
    }

    @Override
    public boolean isRunning() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void start() {
        String effectorName = entity.config().get(CustomMachine.DELEGATE_START_EFFECTOR_NAME);
        if (effectorName != null) {
            Maybe<Effector<?>> effector = entity.getEntityType().getEffectorByName(effectorName);
            if (effector.isAbsent()) {
                throw new IllegalStateException("Delegate start-effector '"+effectorName+"' not found on "+entity);
            }
    
            DynamicTasks.queue("doStart", new Runnable() { @Override public void run() {
                try (CloseableLatch value = waitForLatch(BrooklynConfigKeys.START_LATCH)) {
                    entity.invoke(effector.get(), ImmutableMap.of()).getUnchecked();
                }
            }});
        }
    }

    @Override
    public void stop() {
        String effectorName = entity.config().get(CustomMachine.DELEGATE_STOP_EFFECTOR_NAME);
        if (effectorName != null) {
            Maybe<Effector<?>> effector = entity.getEntityType().getEffectorByName(effectorName);
            if (effector.isAbsent()) {
                throw new IllegalStateException("Delegate stop-effector '"+effectorName+"' not found on "+entity);
            }
    
            DynamicTasks.queue("doStop", new Runnable() { @Override public void run() {
                try (CloseableLatch value = waitForLatch(BrooklynConfigKeys.STOP_LATCH)) {
                    entity.invoke(effector.get(), ImmutableMap.of()).getUnchecked();
                }
            }});
        }
    }

    @Override
    public void restart() {
        String effectorName = entity.config().get(CustomMachine.DELEGATE_RESTART_EFFECTOR_NAME);
        if (effectorName != null) {
            Maybe<Effector<?>> effector = entity.getEntityType().getEffectorByName(effectorName);
            if (effector.isAbsent()) {
                throw new IllegalStateException("Delegate restart-effector '"+effectorName+"' not found on "+entity);
            }
    
            DynamicTasks.queue("doRestart", new Runnable() { @Override public void run() {
                entity.invoke(effector.get(), ImmutableMap.of()).getUnchecked();
            }});
        }
    }

    @Override
    public void kill() {
        stop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public EntityLocal getEntity() {
        return (EntityLocal) entity;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    private CloseableLatch waitForLatch(ConfigKey<Boolean> configKey) {
        return MachineLifecycleEffectorTasks.waitForCloseableLatch((EntityInternal)entity, configKey);
    }
}

