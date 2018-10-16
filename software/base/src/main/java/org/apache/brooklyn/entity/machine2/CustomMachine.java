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

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.config.MapConfigKey;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle.Transition;
import org.apache.brooklyn.core.entity.trait.Startable;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.entity.software.base.EmptySoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.entity.software.base.SoftwareProcess.ChildStartableMode;
import org.apache.brooklyn.entity.software.base.SoftwareProcessDriver;
import org.apache.brooklyn.util.time.Duration;

import com.google.common.annotations.Beta;

/**
 * Represents a machine for which there is no assumption about connectivity or ssh/winrm.
 * 
 * Blueprint developers using this can define effectors to be called on start  
 * (after the machine is obtained), and on stop (before the machine is released). 
 * The effector to be called on start and on stop is set using {@code delegateStartEffectorName}
 * and {@code delegateStopEffectorName} respectively. If not set, no delegate effector will 
 * be called.
 * 
 * The entity lifecycle of setting {@code service.state} and {@code service.state.expected} is handled
 * by this entity.
 * 
 * In some ways it is similar to {@link EmptySoftwareProcess}, but by not extending {@link SoftwareProcess}
 * it does not pull in the many configuration options such as the 
 * {@code install -> customize -> launch} sequence, or the {@code *.command} configuration options.
 * 
 * The default configuration tells the location to not wait for an ssh/winrm port / connection.
 * It has the default value of {@code onbox.base.dir.skipResolution} as {@code true}.
 * It also sets the following provisioning.properties (if absent):
 * <ul>
 *   <li>{@code waitForSshable: "false"}
 *   <li>{@code waitForWinRmAvailable: "false"}
 *   <li>{@code pollForFirstReachableAddress: "false"}
 * </ul>
 * 
 * Example usage:
 * 
 * <pre>
 * {@code
 * services:
 *   - type: org.apache.brooklyn.entity.machine2.CustomMachine
 *     brooklyn.config:
 *       delegateStartEffectorName: doStart
 *       delegateStopEffectorName: doStop
 *     brooklyn.initializers:
 *       - type: org.apache.brooklyn.core.effector.http.HttpCommandEffector
 *         brooklyn.config:
 *           name: doStart
 *           uri:
 *             $brooklyn:formatString:
 *               - "https://%s/startsomething"
 *               - $brooklyn:attributeWhenReady("host.name")
 *           httpVerb: POST
 *       - type: org.apache.brooklyn.core.effector.http.HttpCommandEffector
 *         brooklyn.config:
 *           name: doStop
 *           uri:
 *             $brooklyn:formatString:
 *               - "https://%s/stopsomething"
 *               - $brooklyn:attributeWhenReady("host.name")
 *           httpVerb: POST
 *       - type: org.apache.brooklyn.core.sensor.http.HttpRequestSensor
 *         brooklyn.config:
 *           name: healthy
 *           period: 10s
 *           type: Boolean
 *           jsonPath: $.health
 *           uri:
 *             $brooklyn:formatString:
 *               - "https://%s/stopsomething"
 *               - $brooklyn:attributeWhenReady("host.name")
 *     brooklyn.enrichers:
 *       - type: org.apache.brooklyn.enricher.stock.UpdatingMap
 *         brooklyn.config:
 *           enricher.sourceSensor: $brooklyn:sensor("healthy")
 *           enricher.targetSensor: $brooklyn:sensor("service.notUp.indicators")
 *           enricher.updatingMap.computing:
 *             $brooklyn:object:
 *               type: com.google.common.base.Functions
 *               factoryMethod.name: forMap
 *               factoryMethod.args:
 *                 - false: "false"
 *                   true: null
 *                 - "no value"
 * }
 * </pre>
 */
@ImplementedBy(CustomMachineImpl.class)
public interface CustomMachine extends Entity, Startable {

    ConfigKey<String> DELEGATE_START_EFFECTOR_NAME = ConfigKeys.newStringConfigKey("delegateStartEffectorName");

    ConfigKey<String> DELEGATE_STOP_EFFECTOR_NAME = ConfigKeys.newStringConfigKey("delegateStopEffectorName");

    ConfigKey<String> DELEGATE_RESTART_EFFECTOR_NAME = ConfigKeys.newStringConfigKey("delegateRestartEffectorName");

    ConfigKey<Duration> START_TIMEOUT = BrooklynConfigKeys.START_TIMEOUT;

    ConfigKey<Boolean> START_LATCH = BrooklynConfigKeys.START_LATCH;

    ConfigKey<Boolean> STOP_LATCH = BrooklynConfigKeys.STOP_LATCH;

    MapConfigKey<Object> PROVISIONING_PROPERTIES = BrooklynConfigKeys.PROVISIONING_PROPERTIES;

    ConfigKey<ChildStartableMode> CHILDREN_STARTABLE_MODE = ConfigKeys.newConfigKey(ChildStartableMode.class,
            "children.startable.mode", "Controls behaviour when starting Startable children as part of this entity's lifecycle.",
            ChildStartableMode.NONE);

    @Beta
    ConfigKey<CustomMachineDriverLifecycleEffectorTasks> LIFECYCLE_EFFECTOR_TASKS = ConfigKeys.newConfigKey(CustomMachineDriverLifecycleEffectorTasks.class,
            "softwareProcess.lifecycleTasks", "An object that handles lifecycle of an entity's associated machine.",
            new CustomMachineDriverLifecycleEffectorTasks());

    ConfigKey<Boolean> SKIP_ON_BOX_BASE_DIR_RESOLUTION = ConfigKeys.newConfigKeyWithDefault(
            BrooklynConfigKeys.SKIP_ON_BOX_BASE_DIR_RESOLUTION, 
            true);

    @SuppressWarnings("rawtypes")
    AttributeSensor<MachineProvisioningLocation> PROVISIONING_LOCATION = Sensors.newSensor(
            MachineProvisioningLocation.class, "softwareservice.provisioningLocation", "Location used to provision a machine where this is running");

    AttributeSensor<Lifecycle> SERVICE_STATE_ACTUAL = Attributes.SERVICE_STATE_ACTUAL;
    AttributeSensor<Transition> SERVICE_STATE_EXPECTED = Attributes.SERVICE_STATE_EXPECTED;
    AttributeSensor<String> HOSTNAME = Attributes.HOSTNAME;
    AttributeSensor<String> ADDRESS = Attributes.ADDRESS;
    AttributeSensor<String> SUBNET_HOSTNAME = Attributes.SUBNET_HOSTNAME;
    AttributeSensor<String> SUBNET_ADDRESS = Attributes.SUBNET_ADDRESS;


    // NB: the START, STOP, and RESTART effectors themselves are (re)defined by MachineLifecycleEffectorTasks
    
    public SoftwareProcessDriver getDriver();
}
