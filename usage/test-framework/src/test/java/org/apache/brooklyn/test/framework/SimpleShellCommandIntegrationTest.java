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
package org.apache.brooklyn.test.framework;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.LocationSpec;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.test.BrooklynAppUnitTestSupport;
import org.apache.brooklyn.core.test.entity.TestEntity;
import org.apache.brooklyn.location.localhost.LocalhostMachineProvisioningLocation;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.apache.brooklyn.test.framework.BaseTest.TARGET_ENTITY;
import static org.apache.brooklyn.test.framework.SimpleShellCommand.COMMAND;
import static org.apache.brooklyn.test.framework.SimpleShellCommandTest.*;
import static org.assertj.core.api.Assertions.assertThat;

public class SimpleShellCommandIntegrationTest extends BrooklynAppUnitTestSupport {

    private static final String UP = "up";
    private LocalhostMachineProvisioningLocation localhost;

    protected void setUpApp() {
        super.setUpApp();
        localhost = app.getManagementContext().getLocationManager()
            .createLocation(LocationSpec.create(LocalhostMachineProvisioningLocation.class));
    }

    @DataProvider(name = "shouldInsistOnJustOneOfCommandAndScript")
    public Object[][] createData1() {

        return new Object[][] {
            { "pwd", "pwd.sh", Boolean.FALSE },
            { null, null, Boolean.FALSE },
            { "pwd", null, Boolean.TRUE },
            { null, "pwd.sh", Boolean.TRUE }
        };
    }

    @Test(dataProvider = "shouldInsistOnJustOneOfCommandAndScript")
    public void shouldInsistOnJustOneOfCommandAndScript(String command, String script, boolean valid) throws  Exception{
        Path scriptPath = null;
        String scriptUrl = null;
        if (null != script) {
            scriptPath = createTempScript("pwd", "pwd");
            scriptUrl = "file:" + scriptPath;
        }
        TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, command)
            .configure(DOWNLOAD_URL, scriptUrl));

        try {
            app.start(ImmutableList.of(localhost));
            if (!valid) {
                Asserts.shouldHaveFailedPreviously();
            }

        } catch (Exception e) {
            Asserts.expectedFailureContains(e, "Must specify exactly one of download.url and command");

        } finally {
            if (null != scriptPath) {
                Files.delete(scriptPath);
            }
        }
    }

    @Test(groups = "Integration")
    public void shouldInvokeCommand() {
        TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        SimpleShellCommandTest uptime = app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, "uptime")
            .configure(ASSERT_STATUS, ImmutableMap.of(EQUALS, 0))
            .configure(ASSERT_OUT, ImmutableMap.of(CONTAINS, UP)));

        app.start(ImmutableList.of(localhost));

        assertThat(uptime.sensors().get(SERVICE_UP)).isTrue()
            .withFailMessage("Service should be up");
        assertThat(ServiceStateLogic.getExpectedState(uptime)).isEqualTo(Lifecycle.RUNNING)
            .withFailMessage("Service should be marked running");

    }

    @Test(groups = "Integration")
    public void shouldNotBeUpIfAssertionFails() {
        TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        SimpleShellCommandTest uptime = app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
            .configure(TARGET_ENTITY, testEntity)
            .configure(COMMAND, "uptime")
            .configure(ASSERT_STATUS, ImmutableMap.of(EQUALS, 1)));

        try {
            app.start(ImmutableList.of(localhost));
        } catch (Exception e) {
            assertThat(e.getCause().getMessage().contains("exit code equals 1"));
        }

        assertThat(ServiceStateLogic.getExpectedState(uptime)).isEqualTo(Lifecycle.ON_FIRE)
            .withFailMessage("Service should be marked on fire");

    }

    @Test(groups = "Integration")
    public void shouldInvokeScript() throws Exception {
        TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        String text = "hello world";
        Path testScript = createTempScript("script", "echo " + text);

        try {
            SimpleShellCommandTest uptime = app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
                .configure(TARGET_ENTITY, testEntity)
                .configure(DOWNLOAD_URL, "file:" + testScript)
                .configure(ASSERT_STATUS, ImmutableMap.of(EQUALS, 0))
                .configure(ASSERT_OUT, ImmutableMap.of(CONTAINS, text)));

            app.start(ImmutableList.of(localhost));

            assertThat(uptime.sensors().get(SERVICE_UP)).isTrue()
                .withFailMessage("Service should be up");
            assertThat(ServiceStateLogic.getExpectedState(uptime)).isEqualTo(Lifecycle.RUNNING)
                .withFailMessage("Service should be marked running");

        } finally {
            Files.delete(testScript);
        }
    }

    @Test
    public void shouldExecuteInTheRunDir() throws Exception {
        TestEntity testEntity = app.createAndManageChild(EntitySpec.create(TestEntity.class));

        Path pwdPath = createTempScript("pwd", "pwd");

        try {
            SimpleShellCommandTest pwd = app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
                .configure(TARGET_ENTITY, testEntity)
                .configure(DOWNLOAD_URL, "file:" + pwdPath)
                .configure(RUN_DIR, "/tmp")
                .configure(ASSERT_STATUS, ImmutableMap.of(EQUALS, 0))
                .configure(ASSERT_OUT, ImmutableMap.of(CONTAINS, "/tmp")));


            SimpleShellCommandTest alsoPwd = app.createAndManageChild(EntitySpec.create(SimpleShellCommandTest.class)
                .configure(TARGET_ENTITY, testEntity)
                .configure(COMMAND, "pwd")
                .configure(RUN_DIR, "/tmp")
                .configure(ASSERT_STATUS, ImmutableMap.of(EQUALS, 0))
                .configure(ASSERT_OUT, ImmutableMap.of(CONTAINS, "/tmp")));

            app.start(ImmutableList.of(localhost));

            assertThat(pwd.sensors().get(SERVICE_UP)).isTrue().withFailMessage("Service should be up");
            assertThat(ServiceStateLogic.getExpectedState(pwd)).isEqualTo(Lifecycle.RUNNING)
                .withFailMessage("Service should be marked running");

            assertThat(alsoPwd.sensors().get(SERVICE_UP)).isTrue().withFailMessage("Service should be up");
            assertThat(ServiceStateLogic.getExpectedState(alsoPwd)).isEqualTo(Lifecycle.RUNNING)
                .withFailMessage("Service should be marked running");

        } finally {
            Files.delete(pwdPath);
        }
    }

    private Path createTempScript(String filename, String contents) {
        try {
            Path tempFile = Files.createTempFile("SimpleShellCommandIntegrationTest-" + filename, ".sh");
            Files.write(tempFile, contents.getBytes());
            return tempFile;
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
    }

}
