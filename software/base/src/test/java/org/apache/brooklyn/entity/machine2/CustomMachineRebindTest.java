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

import static org.apache.brooklyn.entity.machine2.CustomMachineTest.sensorFeed;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.mgmt.rebind.RebindTestFixtureWithApp;
import org.apache.brooklyn.entity.machine2.CustomMachineTest.DummyEffector;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

public class CustomMachineRebindTest extends RebindTestFixtureWithApp {

    @Override
    protected boolean enablePersistenceBackups() {
        return false;
    }

    @Test
    public void testRebind() throws Exception {
        SshMachineLocation origMachine = mgmt().getLocationManager().createLocation(LocationSpec.create(SshMachineLocation.class)
                .configure("address", "localhost"));
        
        app().createAndManageChild(EntitySpec.create(CustomMachine.class)
                .configure(CustomMachine.DELEGATE_START_EFFECTOR_NAME, "myStart")
                .configure(CustomMachine.DELEGATE_STOP_EFFECTOR_NAME, "myStop")
                .addInitializer(new DummyEffector(ConfigBag.newInstance().configure(DummyEffector.EFFECTOR_NAME, "myStart")))
                .addInitializer(new DummyEffector(ConfigBag.newInstance().configure(DummyEffector.EFFECTOR_NAME, "myStop")))
                .addInitializer(sensorFeed(Attributes.SERVICE_UP, new AtomicReference<>(true))));

        origApp.start(ImmutableList.of(origMachine));
        
        newApp = rebind();
        
        newApp.stop();
    }
}
