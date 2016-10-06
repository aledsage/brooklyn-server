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
package org.apache.brooklyn.location.jclouds;

import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.domain.TemplateBuilder;
import org.jclouds.compute.options.TemplateOptions;

/**
 * Customization hooks to allow apps to perform specific customisation at each stage of jclouds machine provisioning.
 * For example, an app could attach an EBS volume to an EC2 node, or configure a desired availability zone.
 * <p>
 * Users are strongly encouraged to sub-class {@link org.apache.brooklyn.location.jclouds.BasicJcloudsLocationCustomizer}, 
 * to give some protection against this API changing in future releases.
 * <p>
 * Customizers can be instantiated on-demand, so the {@link #postRelease(JcloudsMachineLocation)}
 * and {@link #postRelease(JcloudsMachineLocation)} methods may not be called on the same instance 
 * as was used in provisioning. This is always true after a Brooklyn restart, and may be true at 
 * other times depending how the customizer has been wired in.
 * <p>
 * However the customize functions will be called sequentially on the same instance during provisioning,
 * unless Brooklyn is stopped (or fails over to a high-availability standby), in which case VM 
 * provisioning would abort anyway.
 */
public interface JcloudsLocationCustomizer {

    /**
     * Override to configure {@link org.jclouds.compute.domain.TemplateBuilder templateBuilder}
     * before it is built and immutable.
     */
    void customize(JcloudsLocation location, ComputeService computeService, TemplateBuilder templateBuilder);

    /**
     * Override to configure a subclass of this with the built template, or to configure the built
     * template's {@link org.jclouds.compute.options.TemplateOptions}.
     * <p>
     * This method will be called before {@link #customize(JcloudsLocation, ComputeService, TemplateOptions)}.
     */
    void customize(JcloudsLocation location, ComputeService computeService, Template template);

    /**
     * Override to configure the {@link org.jclouds.compute.options.TemplateOptions} that will
     * be used by {@link JcloudsLocation} to obtain machines.
     */
    void customize(JcloudsLocation location, ComputeService computeService, TemplateOptions templateOptions);

    /**
     * Override to configure the given machine once it has been created and started by Jclouds.
     * <p>
     * If {@link JcloudsLocationConfig#WAIT_FOR_SSHABLE} is true the machine is guaranteed to be
     * SSHable when this method is called.
     */
    void customize(JcloudsLocation location, ComputeService computeService, JcloudsMachineLocation machine);

    /**
     * Override to handle machine-related cleanup before Jclouds is called to release (destroy) the machine.
     */
    void preRelease(JcloudsMachineLocation machine);

    /**
     * Override to handle machine-related cleanup after Jclouds is called to release (destroy) the machine.
     */
    void postRelease(JcloudsMachineLocation machine);
}
