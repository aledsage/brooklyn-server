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
package org.apache.brooklyn.util.groovy;

import static org.apache.brooklyn.util.groovy.GroovyJavaMethods.elvis;
import static org.apache.brooklyn.util.groovy.GroovyJavaMethods.truth;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;

import org.codehaus.groovy.runtime.GStringImpl;
import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;

import groovy.lang.Closure;
import groovy.lang.GString;

public class GroovJavaMethodsTest {

    private String gstringVal = "exampleGString";
    private GString gstring = new GStringImpl(new Object[0], new String[] {gstringVal});
    private GString emptyGstring = new GStringImpl(new Object[0], new String[] {""});

    @Test
    public void testTruth() {
        assertFalse(truth(null));
        assertTrue(truth("someString"));
        assertFalse(truth(""));
        assertTrue(truth(1));
        assertFalse(truth(0));
        assertTrue(truth(true));
        assertFalse(truth(false));
        assertTrue(truth(gstring));
        assertFalse(truth(emptyGstring));
    }

    @Test
    public void testElvis() {
        final List<?> emptyList = ImmutableList.of();
        final List<?> singletonList = ImmutableList.of("myVal");
        final List<?> differentList = ImmutableList.of("differentVal");
        
        assertEquals(elvis("", "string2"), "string2");
        assertEquals(elvis("string1", "string2"), "string1");
        assertEquals(elvis(null, "string2"), "string2");
        assertEquals(elvis("", "string2"), "string2");
        assertEquals(elvis(1, 2), 1);
        assertEquals(elvis(0, 2), 2);
        assertEquals(elvis(singletonList, differentList), singletonList);
        assertEquals(elvis(emptyList, differentList), differentList);
        assertEquals(elvis(gstring, "other"), gstringVal);
        assertEquals(elvis(emptyGstring, "other"), "other");
    }

    @Test
    public void testIsCase() throws Throwable {
        assertFalse(callScriptBytecodeAdapter_isCase(
                null,
                GString.class));
        assertTrue(
                callScriptBytecodeAdapter_isCase(
                        gstring,
                        GString.class));
        assertFalse(
                callScriptBytecodeAdapter_isCase(
                        "exampleString",
                        GString.class));

        assertTrue(
                callScriptBytecodeAdapter_isCase(
                        new Callable<Void>() {
                            @Override public Void call() {
                                return null;
                            }
                        },
                        Callable.class));
        assertFalse(
                callScriptBytecodeAdapter_isCase(
                        "exampleString",
                        Callable.class));

        assertTrue(
                callScriptBytecodeAdapter_isCase(
                        new Closure<Void>(null) {
                            @Override public Void call() {
                                return null;
                            }
                        },
                        Closure.class));
        assertFalse(
                callScriptBytecodeAdapter_isCase(
                        "exampleString",
                        Closure.class));
    }

    private boolean callScriptBytecodeAdapter_isCase(Object switchValue, Class<?> caseExpression) throws Throwable {
        // We expect this to be equivalent to:
        //     org.codehaus.groovy.runtime.ScriptBytecodeAdapter.isCase(switchValue, caseExpression);
        boolean result = org.apache.brooklyn.util.groovy.GroovyJavaMethods.scriptBytecodeAdapter_isCase(switchValue, caseExpression);
        boolean equiv = org.codehaus.groovy.runtime.ScriptBytecodeAdapter.safeGroovyIsCase(switchValue, caseExpression);
        assertEquals(result, equiv, "switchValue="+switchValue+"; caseExpression="+caseExpression);
        return result;
    }
}
