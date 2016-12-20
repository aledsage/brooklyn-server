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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.util.os.Os;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

public class DeserializingClassRenamesProviderTest {

    @Test
    public void testLoadsStandardRenames() throws Exception {
        Map<String, String> renames = DeserializingClassRenamesProvider.loadDeserializingClassRenames();
        assertEquals(renames.get("brooklyn.entity.Entity"), Entity.class.getName());
    }
    
    @Test
    @SuppressWarnings("deprecation")
    public void testLegacyResourceName() throws Exception {
        String path = DeserializingClassRenamesProvider.DESERIALIZING_CLASS_RENAMES_PROPERTIES_PATH;
        assertTrue(path.startsWith("classpath://"));
        path = path.substring("classpath://".length());
        String origName = "com.example.brooklyn.origname";
        String newName = "com.example.brooklyn.newname";
        String contents = origName + ": " + newName;
        
        File dir = createClasspathDir(path, contents.getBytes());
        try {
            addToClasspath(dir.toURI().toURL());
            DeserializingClassRenamesProvider.reset();

            Map<String, String> renames = DeserializingClassRenamesProvider.loadDeserializingClassRenames();
            assertEquals(renames.get(origName), newName);
        } finally {
            Os.deleteRecursively(dir);
            DeserializingClassRenamesProvider.reset();
        }
        
        Map<String, String> renames = DeserializingClassRenamesProvider.loadDeserializingClassRenames();
        assertFalse(renames.containsKey(origName));
    }

    @Test
    public void testMultipleFiles() throws Exception {
        String packagePath = DeserializingClassRenamesProvider.DESERIALIZING_CLASS_RENAMES_PROPERTIES_PACKAGE.replaceAll("\\.", File.separator);
        List<Map<String, String>> expectedRenames = Lists.newArrayList();
        List<File> dirs = Lists.newArrayList();
        for (int i = 0; i < 2; i++) {
            String path = Os.mergePaths(packagePath, "myRenames" + i + ".properties");
            String origName = "com.example.brooklyn.OrigName" + i;
            String newName = "com.example.brooklyn.NewName" + i;
            String contents = origName + ": " + newName;
            File dir = createClasspathDir(path, contents.getBytes());
            expectedRenames.add(ImmutableMap.of(origName, newName));
            dirs.add(dir);
        }
        try {
            for (File dir : dirs) {
                addToClasspath(dir.toURI().toURL());
            }
            DeserializingClassRenamesProvider.reset();

            Map<String, String> renames = DeserializingClassRenamesProvider.loadDeserializingClassRenames();
            assertEquals(renames.get("brooklyn.entity.Entity"), Entity.class.getName());
            for (Map<String, String> expectedRename : expectedRenames) {
                for (Map.Entry<String, String> entry : expectedRename.entrySet()) {
                    assertEquals(renames.get(entry.getKey()), entry.getValue());
                }
            }
        } finally {
            for (File dir : dirs) {
                Os.deleteRecursively(dir);
            }
            DeserializingClassRenamesProvider.reset();
        }

        // Confirm we've removed these renames - haven't left anything behind at the end of the test
        Map<String, String> renames = DeserializingClassRenamesProvider.loadDeserializingClassRenames();
        assertEquals(renames.get("brooklyn.entity.Entity"), Entity.class.getName());
        for (Map<String, String> expectedRename : expectedRenames) {
            for (Map.Entry<String, String> entry : expectedRename.entrySet()) {
                assertFalse(renames.containsKey(entry.getKey()));
            }
        }
    }

    private File createClasspathDir(String path, byte[] contents) throws Exception {
        File dir = Files.createTempDir();
        File resource = new File(dir, path);
        File subdir = resource.getParentFile();
        subdir.mkdirs();
        Files.write(contents, resource);
        return dir;
    }

    private void addToClasspath(URL url) throws Exception {
        Method method = URLClassLoader.class.getDeclaredMethod("addURL", new Class[]{URL.class});
        method.setAccessible(true);
        method.invoke(ClassLoader.getSystemClassLoader(), new Object[]{url});
    }
}
