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
package org.apache.brooklyn.core.mgmt.persist;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.brooklyn.util.core.ResourceUtils;
import org.apache.brooklyn.util.exceptions.Exceptions;
import org.apache.brooklyn.util.javalang.Reflections;
import org.apache.brooklyn.util.stream.Streams;
import org.apache.brooklyn.util.text.StringPredicates;
import org.reflections.scanners.ResourcesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.FilterBuilder;

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

@Beta
public class DeserializingClassRenamesProvider {

    public static final String DESERIALIZING_CLASS_RENAMES_PROPERTIES_PACKAGE = "org.apache.brooklyn.core.mgmt.persist.deserializingClassRenames";

    /**
     * @deprecated since 0.11.0; instead use {@link DESERIALIZING_CLASS_RENAMES_PROPERTIES_PACKAGE}
     */
    @Deprecated
    public static final String DESERIALIZING_CLASS_RENAMES_PROPERTIES_PATH = "classpath://org/apache/brooklyn/core/mgmt/persist/deserializingClassRenames.properties";
    
    private static Map<String, String> cache;
    
    @Beta
    public static Map<String, String> loadDeserializingClassRenames() {
        synchronized (DeserializingClassRenamesProvider.class) {
            if (cache == null) {
                cache = loadDeserializingClassRenamesCache();
            }
            return cache;
        }
    }
    
    public static void reset() {
        synchronized (DeserializingClassRenamesProvider.class) {
            cache = null;
        }
    }

    private static Map<String, String> loadDeserializingClassRenamesCache() {
        Set<String> renames = new org.reflections.Reflections(new ConfigurationBuilder()
                .setUrls(ClasspathHelper.forPackage(DESERIALIZING_CLASS_RENAMES_PROPERTIES_PACKAGE))
                .setScanners(new ResourcesScanner())
                .filterInputsBy(new FilterBuilder().includePackage(DESERIALIZING_CLASS_RENAMES_PROPERTIES_PACKAGE)))
                .getResources(StringPredicates.<String>matchesRegex(".*\\.properties$"));

        Map<String, String> result = Maps.newLinkedHashMap();

        try {
            // Add legacy class-renames
            result.putAll(loadProperties(DESERIALIZING_CLASS_RENAMES_PROPERTIES_PATH, false));
    
            for (String renameFile : renames) {
                result.putAll(loadProperties("classpath://" + renameFile, true));
            }
        } catch (IOException e) {
            throw Exceptions.propagate(e);
        }
        return result;
    }

    private static Map<String, String> loadProperties(String url, boolean failIfAbsent) throws IOException {
        InputStream resource;
        try {
            resource = new ResourceUtils(DeserializingClassRenamesProvider.class).getResourceFromUrl(url);
        } catch (RuntimeException e) {
            if (failIfAbsent) {
                throw e;
            } else {
                return ImmutableMap.of(); // resource does not exist
            }
        }
        
        try {
            Properties props = new Properties();
            props.load(resource);

            Map<String, String> result = Maps.newLinkedHashMap();
            for (Enumeration<?> iter = props.propertyNames(); iter.hasMoreElements(); ) {
                String key = (String) iter.nextElement();
                String value = props.getProperty(key);
                result.put(key, value);
            }
            return result;
        } finally {
            Streams.closeQuietly(resource);
        }
    }
    
    @Beta
    public static String findMappedName(String name) {
        return Reflections.findMappedNameAndLog(loadDeserializingClassRenames(), name);
    }
}
