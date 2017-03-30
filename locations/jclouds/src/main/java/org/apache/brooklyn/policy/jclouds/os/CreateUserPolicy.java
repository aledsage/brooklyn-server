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
package org.apache.brooklyn.policy.jclouds.os;

import static org.apache.brooklyn.util.ssh.BashCommands.sbinPath;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.brooklyn.api.effector.Effector;
import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntityLocal;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.sensor.AttributeSensor;
import org.apache.brooklyn.api.sensor.SensorEvent;
import org.apache.brooklyn.api.sensor.SensorEventListener;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.effector.EffectorBody;
import org.apache.brooklyn.core.effector.Effectors;
import org.apache.brooklyn.core.entity.AbstractEntity;
import org.apache.brooklyn.core.entity.EntityInternal;
import org.apache.brooklyn.core.policy.AbstractPolicy;
import org.apache.brooklyn.core.sensor.Sensors;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.core.config.ConfigBag;
import org.apache.brooklyn.util.core.flags.SetFromFlag;
import org.apache.brooklyn.util.core.internal.ssh.SshTool;
import org.apache.brooklyn.util.core.task.BasicTask;
import org.apache.brooklyn.util.core.task.ScheduledTask;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.text.Identifiers;
import org.apache.brooklyn.util.time.Duration;
import org.jclouds.compute.config.AdminAccessConfiguration;
import org.jclouds.scriptbuilder.functions.InitAdminAccess;
import org.jclouds.scriptbuilder.statements.login.AdminAccess;
import org.jclouds.scriptbuilder.statements.ssh.SshdConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * When attached to an entity, this will monitor for when an {@link SshMachineLocation} is added to that entity
 * (e.g. when a VM has been provisioned for it).
 * 
 * The policy will then (asynchronously) add a new user to the VM, with a randomly generated password.
 * The ssh details will be set as a sensor on the entity.
 * 
 * If this is used, it is strongly encouraged to tell users to change the password on first login.
 * 
 * A preferred mechanism would be for an external key-management tool to generate ssh key-pairs for
 * the user, and for the public key to be passed to Brooklyn. However, there is not a customer 
 * requirement for that yet, so focusing on the password-approach.
 */
@Beta
public class CreateUserPolicy extends AbstractPolicy implements SensorEventListener<Location> {

    // TODO Should add support for authorizing ssh keys as well
    
    // TODO Should review duplication with:
    //  - JcloudsLocationConfig.GRANT_USER_SUDO
    //    (but config default/description and context of use are different)
    //  - AdminAccess in JcloudsLocation.createUserStatements
    
    // TODO Could make the password explicitly configurable, or auto-generate if not set?
    
    // TODO Scheduling of password-reset is illustrative code only; it is untested and not
    // cleaned up.
    // 
    // The idea is: when the policy is created, e.g. to change the password every 21 days, it 
    // schedules a task to execute in 21 days. When that task executes, it changes the password
    // and then schedules itself again for another 21 days time.
    // If Brooklyn is restarted in that time, our task will be lost. On rebind, it therefore
    // re-schedules the task. It stores the "next-time" as an internal config value so it knows
    // when to reschedule for.
    //
    // The implementation relies on config().set(CHANGE_PASSWORD_NEXT_TIME) causing 
    // doReconfigureConfig() to be called.
    // 
    // The ability to reconfigure the changePasswordPeriod on-the-fly makes things more complicated
    // (including for testing - maybe we shouldn't support that yet?).
    // It re-computes when the password should expire, and resets it.
    
    private static final Logger LOG = LoggerFactory.getLogger(CreateUserPolicy.class);

    @SetFromFlag("user")
    public static final ConfigKey<String> VM_USERNAME = ConfigKeys.newStringConfigKey("createuser.vm.user.name");

    @SetFromFlag("grantSudo")
    public static final ConfigKey<Boolean> GRANT_SUDO = ConfigKeys.newBooleanConfigKey(
            "createuser.vm.user.grantSudo",
            "Whether to give the new user sudo rights",
            false);

    public static final AttributeSensor<String> VM_USER_CREDENTIALS = Sensors.newStringSensor(
            "createuser.vm.user.credentials",
            "The \"<user> : <password> @ <hostname>:<port>\"");

    @SetFromFlag("resetLoginUser")
    public static final ConfigKey<Boolean> RESET_LOGIN_USER = ConfigKeys.newBooleanConfigKey(
            "createuser.vm.user.resetLoginUser",
            "Whether to reset the password used for user login",
            false);

    public static final ConfigKey<Duration> CHANGE_PASSWORD_PERIOD = ConfigKeys.newConfigKey(
            Duration.class,
            "createuser.vm.user.changePasswordPeriod",
            "The length of time between automatically changing the password (or null to disable)",
            null);

    public static final ConfigKey<Date> CHANGE_PASSWORD_NEXT_TIME = ConfigKeys.newConfigKey(
            Date.class,
            "createuser.vm.user.changePasswordNextTime",
            "the date when the password will next be reset (or null); internal - set automatically based on changePasswordPeriod",
            null);

    private ScheduledTask changePasswordScheduledTask;
    
    @Override
    public void setEntity(EntityLocal entity) {
        super.setEntity(entity);
        subscriptions().subscribe(entity, AbstractEntity.LOCATION_ADDED, this);
        
        // Schedule the password-change, if configured to do so.
        //
        // This code is called when first creating the policy, and also on rebind.
        // For the latter, we must schedule our task again, to execute at the "next time".
        initChangePasswordSchedule();
        
        if (!isRebinding()) {
            // Add the updateUserPassword effector to the entity
            addUpdateUserPasswordEffector();
        }
    }

    @Override
    public void onEvent(SensorEvent<Location> event) {
        final Entity entity = event.getSource();
        final Location loc = event.getValue();
        if (loc instanceof SshMachineLocation) {
            addUserAsync(entity, (SshMachineLocation)loc);
        }
    }

    protected void addUserAsync(final Entity entity, final SshMachineLocation machine) {
        ((EntityInternal)entity).getExecutionContext().execute(new Runnable() {
            @Override
            public void run() {
                addUser(entity, machine);
            }});
    }
    
    protected void addUser(Entity entity, SshMachineLocation machine) {
        boolean grantSudo = getRequiredConfig(GRANT_SUDO);
        boolean resetPassword = getRequiredConfig(RESET_LOGIN_USER);
        String user = getRequiredConfig(VM_USERNAME);
        String password = Identifiers.makeRandomId(12);
        String hostname = machine.getAddress().getHostName();
        int port = machine.getPort();
        String creds = user + " : " + password + " @ " +hostname + ":" + port;
        
        LOG.info("Adding auto-generated user "+user+" @ "+hostname+":"+port);
        
        // Build the command to create the user
        // Note AdminAccess requires _all_ fields set, due to http://code.google.com/p/jclouds/issues/detail?id=1095
        // If jclouds grants Sudo rights, it overwrites the /etc/sudoers, which makes integration tests very dangerous! Not using it.
        AdminAccess adminAccess = AdminAccess.builder()
                .adminUsername(user)
                .adminPassword(password)
                .grantSudoToAdminUser(false)
                .resetLoginPassword(resetPassword)
                .loginPassword(password)
                .authorizeAdminPublicKey(false)
                .adminPublicKey("ignored")
                .installAdminPrivateKey(false)
                .adminPrivateKey("ignore")
                .lockSsh(false)
                .build();
        
        org.jclouds.scriptbuilder.domain.OsFamily scriptOsFamily = (machine.getMachineDetails().getOsDetails().isWindows()) 
                ? org.jclouds.scriptbuilder.domain.OsFamily.WINDOWS
                : org.jclouds.scriptbuilder.domain.OsFamily.UNIX;
        
        InitAdminAccess initAdminAccess = new InitAdminAccess(new AdminAccessConfiguration.Default());
        initAdminAccess.visit(adminAccess);
        String cmd = adminAccess.render(scriptOsFamily);

        // Exec command to create the user
        int result = machine.execScript(ImmutableMap.of(SshTool.PROP_RUN_AS_ROOT.getName(), true), "create-user-"+user, ImmutableList.of(cmd), ImmutableMap.of("PATH", sbinPath()));
        if (result != 0) {
            throw new IllegalStateException("Failed to auto-generate user, using command "+cmd);
        }

        // Exec command to grant password-access to sshd (which may have been disabled earlier).
        cmd = new SshdConfig(ImmutableMap.of("PasswordAuthentication", "yes")).render(scriptOsFamily);
        result = machine.execScript(ImmutableMap.of(SshTool.PROP_RUN_AS_ROOT.getName(), true), "create-user-"+user, ImmutableList.of(cmd), ImmutableMap.of("PATH", sbinPath()));
        if (result != 0) {
            throw new IllegalStateException("Failed to enable ssh-login-with-password, using command "+cmd);
        }

        // Exec command to grant sudo rights.
        if (grantSudo) {
            List<String> cmds = ImmutableList.of(
                    "cat >> /etc/sudoers <<-'END_OF_JCLOUDS_FILE'\n"+
                            user+" ALL = (ALL) NOPASSWD:ALL\n"+
                            "END_OF_JCLOUDS_FILE\n",
                    "chmod 0440 /etc/sudoers");
            result = machine.execScript(ImmutableMap.of(SshTool.PROP_RUN_AS_ROOT.getName(), true), "add-user-to-sudoers-"+user, cmds, ImmutableMap.of("PATH", sbinPath()));
            if (result != 0) {
                throw new IllegalStateException("Failed to auto-generate user, using command "+cmds);
            }
        }
        
        entity.sensors().set(VM_USER_CREDENTIALS, creds);
    }
    
    protected void changePasswordNow() {
        // TODO Execute over ssh jclouds `ReplaceShadowPasswordEntry`
        // Also call entity.sensors().set(VM_USER_CREDENTIALS, ...)
    }

    protected void updatePasswordSensor(String newPassword) {
        // TODO Call entity.sensors().set(VM_USER_CREDENTIALS, ...)
    }
    
    @Override
    protected <T> void doReconfigureConfig(ConfigKey<T> key, T val) {
        if (key.equals(CHANGE_PASSWORD_NEXT_TIME)) {
            if (val == null) {
                cancelChangePasswordScheduledTask();
            } else {
                scheduleChangePassword((Date)val);
            }
        } else if (key.equals(CHANGE_PASSWORD_PERIOD)) {
            if (val == null) {
                config().set(CHANGE_PASSWORD_NEXT_TIME, null);
            } else {
                Date nextTime = config().get(CHANGE_PASSWORD_NEXT_TIME);
                if (nextTime == null) {
                    Date newNextTime = new Date(System.currentTimeMillis() + ((Duration)val).toMilliseconds());
                    config().set(CHANGE_PASSWORD_NEXT_TIME, newNextTime);
                } else {
                    Duration oldPeriod = config().get(CHANGE_PASSWORD_PERIOD);
                    long diffPeriod = ((Duration)val).subtract(oldPeriod).toMilliseconds();
                    long newNextTimestamp = nextTime.getTime() + diffPeriod;
                    Date newNextTime = (newNextTimestamp < System.currentTimeMillis()) ? new Date() : new Date(newNextTimestamp);
                    config().set(CHANGE_PASSWORD_NEXT_TIME, newNextTime);
                }
            }
        } else {
            super.doReconfigureConfig(key, val);
        }
    }
    
    private void cancelChangePasswordScheduledTask() {
        if (changePasswordScheduledTask != null) {
            changePasswordScheduledTask.cancel();
            changePasswordScheduledTask = null;
        }
    }
    
    private void scheduleChangePassword(Date when) {
        cancelChangePasswordScheduledTask();
        
        long delayMillis = when.getTime() - System.currentTimeMillis();
        Duration delay = (delayMillis <= 0) ? Duration.ZERO : Duration.millis(delayMillis);
        
        Callable<Task<?>> taskFactory = new Callable<Task<?>>() {
            @Override public Task<?> call() {
                return new BasicTask<Void>(new Runnable() {
                    @Override public void run() {
                        try {
                            changePasswordNow();
                            reschedule(config().get(CHANGE_PASSWORD_PERIOD));
                        } catch (Exception e) {
                            Exceptions.propagateIfFatal(e);
                            Duration period = config().get(CHANGE_PASSWORD_PERIOD);
                            if (period == null) {
                                LOG.warn("Problem reseting password for "+getEntity()+"; will not retry because "
                                        + CHANGE_PASSWORD_PERIOD.getName()+" is now null", e);
                            } else {
                                Duration delay = Duration.FIVE_MINUTES;
                                LOG.warn("Problem reseting password for "+getEntity()+"; will retry in "+delay, e);
                                reschedule(delay);
                            }
                        }
                    }
                    private void reschedule(Duration delay) {
                        if (delay != null) {
                            Date changePasswordNextTime = new Date(System.currentTimeMillis() + delay.toMilliseconds());
                            config().set(CHANGE_PASSWORD_NEXT_TIME, changePasswordNextTime);
                        } else {
                            config().set(CHANGE_PASSWORD_NEXT_TIME, null);
                        }
                    }});
            }};

        LOG.info("Scheduling change-password for {} in {}", getEntity(), delay);
        ((EntityInternal)entity).getExecutionContext().submit(new ScheduledTask(taskFactory).delay(delay));
        changePasswordScheduledTask = new ScheduledTask(taskFactory).delay(delay);
    }

    private void initChangePasswordSchedule() {
        Duration changePasswordPeriod = config().get(CHANGE_PASSWORD_PERIOD);
        Date changePasswordNextTime = config().get(CHANGE_PASSWORD_NEXT_TIME);
        if (changePasswordPeriod != null) {
            if (changePasswordNextTime == null) {
                changePasswordNextTime = new Date(System.currentTimeMillis() + changePasswordPeriod.toMilliseconds());
                config().set(CHANGE_PASSWORD_NEXT_TIME, changePasswordNextTime); // this calls doReconfigureConfig
            } else {
                scheduleChangePassword(changePasswordNextTime);
            }
        }
    }
    
    private void addUpdateUserPasswordEffector() {
        final ConfigKey<String> newPasswordParam = ConfigKeys.newStringConfigKey("newPassword");
        Effector<Void> updateUserPasswordEffector = Effectors.effector(Void.class, "updateUserPassword")
                .parameter(newPasswordParam)
                .impl(new EffectorBody<Void>() {
                    @Override
                    public Void call(ConfigBag parameters) {
                        String newPassword = parameters.get(newPasswordParam);
                        // TODO null-checks, etc
                        updatePasswordSensor(newPassword);
                        return null;
                    }})
                .build();

        ((EntityInternal)entity).getMutableEntityType().addEffector(updateUserPasswordEffector);
    }
}
