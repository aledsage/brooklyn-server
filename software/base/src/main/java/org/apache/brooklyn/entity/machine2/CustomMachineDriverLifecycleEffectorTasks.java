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

import java.util.Map;

import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.mgmt.TaskAdaptable;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.entity.trait.StartableMethods;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcess.ChildStartableMode;
import org.apache.brooklyn.entity.software.base.SoftwareProcess.RestartSoftwareParameters;
import org.apache.brooklyn.entity.software.base.SoftwareProcess.RestartSoftwareParameters.RestartMachineMode;
import org.apache.brooklyn.entity.software.base.lifecycle.MachineLifecycleEffectorTasks;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.base.Supplier;

/**
 * Thin shim delegating to driver to do start/stop/restart, wrapping as tasks,
 * with common code pulled up to {@link MachineLifecycleEffectorTasks} for non-driver usage.
 */
@Beta
public class CustomMachineDriverLifecycleEffectorTasks extends MachineLifecycleEffectorTasks {

    // TODO Moving forwards, if modifying Apache Brooklyn, we could use SoftwareProcessDriverLifecycleEffectorTasks
    //      (if that did not cast the entity to `SoftwareProcessImpl`).

    private static final Logger log = LoggerFactory.getLogger(CustomMachineDriverLifecycleEffectorTasks.class);
    
    @Override
    protected CustomMachineImpl entity() {
        return (CustomMachineImpl) super.entity();
    }
    
    @Override
    protected Map<String, Object> obtainProvisioningFlags(final MachineProvisioningLocation<?> location) {
        return entity().obtainProvisioningFlags(location);
    }
    
    @Override
    @SuppressWarnings("deprecation")
    protected void preStartCustom(MachineLocation machine) {
        entity().initDriver(machine);

        // Note: must only apply config-sensors after adding to locations and creating driver; 
        // otherwise can't do things like acquire free port from location
        // or allowing driver to set up ports; but must be careful in init not to block on these!
        super.preStartCustom(machine);
    }

    @Override
    protected String startProcessesAtMachine(final Supplier<MachineLocation> machineS) {
        ChildStartableMode mode = getChildrenStartableModeEffective();
        TaskAdaptable<?> children = null;
        if (!mode.isDisabled) {
            children = StartableMethods.startingChildren(entity(), machineS.get());
            // submit rather than queue so it runs in parallel
            // (could also wrap as parallel task with driver.start() -
            // but only benefit is that child starts show as child task,
            // rather than bg task, so not worth the code complexity)
            if (!mode.isLate) Entities.submit(entity(), children);
        }
        
        entity().getDriver().start();
        String result = "Started with driver "+entity().getDriver();
        
        if (!mode.isDisabled) {
            if (mode.isLate) {
                DynamicTasks.waitForLast();
                if (mode.isBackground) {
                    Entities.submit(entity(), children);
                } else {
                    // when running foreground late, there is no harm here in queueing
                    DynamicTasks.queue(children);
                }
            }
            if (!mode.isBackground) children.asTask().getUnchecked();
            result += "; children started "+mode;
        }
        return result;
    }

    @Override
    protected void postStartCustom() {
        entity().waitForServiceUp();
        super.postStartCustom();
    }
    
    @Override
    protected String stopProcessesAtMachine() {
        String result;
        
        ChildStartableMode mode = getChildrenStartableModeEffective();
        TaskAdaptable<?> children = null;
        Exception childException = null;
        
        if (!mode.isDisabled) {
            children = StartableMethods.stoppingChildren(entity());
            
            if (mode.isBackground || !mode.isLate) Entities.submit(entity(), children);
            else {
                DynamicTasks.queue(children);
                try {
                    DynamicTasks.waitForLast();
                } catch (Exception e) {
                    childException = e;
                }
            }
        }
        
        if (entity().getDriver() != null) { 
            entity().getDriver().stop();
            result = "Driver stop completed";
        } else {
            result = "No driver (nothing to do here)";
        }

        if (!mode.isDisabled && !mode.isBackground) {
            try {
                children.asTask().get();
            } catch (Exception e) {
                childException = e;
                log.debug("Error stopping children; continuing and will rethrow if no other errors", e);
            }            
        }
        
        if (childException!=null)
            throw new IllegalStateException(result+"; but error stopping child: "+childException, childException);

        return result;
    }
    
    @Override
    public void restart(ConfigBag parameters) {
        RestartMachineMode isRestartMachine = parameters.get(RestartSoftwareParameters.RESTART_MACHINE_TYPED);
        if (isRestartMachine==null) 
            isRestartMachine=RestartMachineMode.AUTO;
        if (isRestartMachine==RestartMachineMode.AUTO) 
            isRestartMachine = getDefaultRestartStopsMachine() ? RestartMachineMode.TRUE : RestartMachineMode.FALSE; 

        if (isRestartMachine==RestartMachineMode.TRUE) {
            log.debug("restart of "+entity()+" requested be applied at machine level");
            super.restart(parameters);
            return;
        }
        
        ServiceStateLogic.setExpectedState(entity(), Lifecycle.STARTING);
        try {
            log.debug("restart of "+entity()+" appears to have driver and hostname - doing driver-level restart");
            entity().getDriver().restart();
            
            restartChildren(parameters);
            
            DynamicTasks.queue("post-restart", new PostRestartTask());
            DynamicTasks.waitForLast();
            ServiceStateLogic.setExpectedState(entity(), Lifecycle.RUNNING);
        } catch (Throwable t) {
            ServiceStateLogic.setExpectedState(entity(), Lifecycle.ON_FIRE);
            throw Exceptions.propagate(t);
        }
    }

    private class PostRestartTask implements Runnable {
        @Override
        public void run() {
            postRestartCustom();
        }
    }

    /**
     * Default post-start hooks.
     * <p>
     * Can be extended by subclasses, and typically will wait for confirmation of start.
     * The service not set to running until after this. Also invoked following a restart.
     */
    @Override
    protected void postRestartCustom() {
        entity().waitForServiceUp();
        super.postRestartCustom();
    }
    
    @Override
    public String toString(){
        return getClass().getName();
    }
    
    /** returns how children startables should be handled (reporting none for efficiency if there are no children) */
    protected ChildStartableMode getChildrenStartableModeEffective() {
        if (entity().getChildren().isEmpty()) return ChildStartableMode.NONE;
        ChildStartableMode result = entity().getConfig(SoftwareProcess.CHILDREN_STARTABLE_MODE);
        if (result!=null) return result;
        return ChildStartableMode.NONE;
    }
}


