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
package org.apache.brooklyn.launcher;

import org.apache.brooklyn.api.mgmt.ManagementContext;
import org.apache.brooklyn.api.mgmt.ha.HighAvailabilityMode;
import org.apache.brooklyn.api.mgmt.rebind.PersistenceExceptionHandler;
import org.apache.brooklyn.api.mgmt.rebind.RebindManager;
import org.apache.brooklyn.api.mgmt.rebind.mementos.BrooklynMementoRawData;
import org.apache.brooklyn.core.mgmt.ha.OsgiManager;
import org.apache.brooklyn.core.mgmt.internal.LocalManagementContext;
import org.apache.brooklyn.core.mgmt.internal.ManagementContextInternal;
import org.apache.brooklyn.core.mgmt.persist.BrooklynMementoPersisterToObjectStore;
import org.apache.brooklyn.core.mgmt.persist.BrooklynPersistenceUtils;
import org.apache.brooklyn.core.mgmt.persist.PersistMode;
import org.apache.brooklyn.core.mgmt.persist.PersistenceObjectStore;
import org.apache.brooklyn.core.mgmt.rebind.PersistenceExceptionHandlerImpl;
import org.apache.brooklyn.core.mgmt.rebind.RebindManagerImpl;
import org.apache.brooklyn.core.mgmt.rebind.transformer.impl.DeleteOrphanedLocationsTransformer;
import org.apache.brooklyn.core.server.BrooklynServerConfig;
import org.apache.brooklyn.core.server.BrooklynServerPaths;
import org.apache.brooklyn.test.Asserts;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.MutableSet;
import org.apache.brooklyn.util.time.Duration;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.apache.brooklyn.core.internal.BrooklynProperties;

import java.util.Map;
import java.util.Set;

public class CleanOrphanedLocationsLiveTest {

    private String PERSISTED_STATE_PATH_WITH_ORPHANED_LOCATIONS = "/orphaned-locations-test-data/data-with-orphaned-locations";
    private String PERSISTED_STATE_PATH_WITH_MULTIPLE_LOCATIONS_OCCURRENCE = "/orphaned-locations-test-data/fake-multiple-location-for-multiple-search-tests";
    private String PERSISTED_STATE_PATH_WITHOUT_ORPHANED_LOCATIONS = "/orphaned-locations-test-data/data-without-orphaned-locations";
    private String PERSISTED_STATE_DESTINATION_PATH = "/orphaned-locations-test-data/copy-persisted-state-destination";


    private String persistenceDirWithOrphanedLocations;
    private String persistenceDirWithoutOrphanedLocations;
    private String persistenceDirWithMultipleLocationsOccurrence;
    private String destinationDir;
    private String destinationLocation;
    private String localBrooklynProperties;
    private String persistenceLocation;
    private String highAvailabilityMode;

    private BrooklynLauncher launcherWithOrphanedLocations;
    private BrooklynLauncher launcherWithoutOrphanedLocations;
    private DeleteOrphanedLocationsTransformer transformer;

    private ManagementContext managementContext;

    @BeforeMethod
    public void initialize() {
        persistenceDirWithOrphanedLocations = getClass().getResource(PERSISTED_STATE_PATH_WITH_ORPHANED_LOCATIONS).getFile();
        persistenceDirWithoutOrphanedLocations = getClass().getResource(PERSISTED_STATE_PATH_WITHOUT_ORPHANED_LOCATIONS).getFile();
        persistenceDirWithMultipleLocationsOccurrence = getClass().getResource(PERSISTED_STATE_PATH_WITH_MULTIPLE_LOCATIONS_OCCURRENCE).getFile();

        destinationDir = getClass().getResource(PERSISTED_STATE_DESTINATION_PATH).getFile();

        launcherWithOrphanedLocations = BrooklynLauncher.newInstance()
                .localBrooklynPropertiesFile(localBrooklynProperties)
                .brooklynProperties(OsgiManager.USE_OSGI, false)
                .persistMode(PersistMode.AUTO)
                .persistenceDir(persistenceDirWithOrphanedLocations)
                .persistenceLocation(persistenceLocation)
                .highAvailabilityMode(HighAvailabilityMode.HOT_STANDBY);

        launcherWithoutOrphanedLocations = BrooklynLauncher.newInstance()
                .localBrooklynPropertiesFile(localBrooklynProperties)
                .brooklynProperties(OsgiManager.USE_OSGI, false)
                .persistMode(PersistMode.AUTO)
                .persistenceDir(persistenceDirWithoutOrphanedLocations)
                .persistenceLocation(persistenceLocation)
                .highAvailabilityMode(HighAvailabilityMode.HOT_STANDBY);

        transformer = DeleteOrphanedLocationsTransformer.builder().build();
    }

    private void initManagementContextAndPersistence(String persistenceDir) {

        BrooklynProperties brooklynProperties = BrooklynProperties.Factory.builderDefault().build();
        brooklynProperties.put(BrooklynServerConfig.MGMT_BASE_DIR.getName(), "");

        managementContext = new LocalManagementContext(brooklynProperties);

        if (persistenceLocation == null) {
            persistenceLocation = brooklynProperties.getConfig(BrooklynServerConfig.PERSISTENCE_LOCATION_SPEC);
        }

        persistenceDir = BrooklynServerPaths.newMainPersistencePathResolver(brooklynProperties).location(persistenceLocation).dir(persistenceDir).resolve();
        PersistenceObjectStore objectStore = BrooklynPersistenceUtils.newPersistenceObjectStore(managementContext, persistenceLocation, persistenceDir,
                PersistMode.AUTO, HighAvailabilityMode.HOT_STANDBY);

        BrooklynMementoPersisterToObjectStore persister = new BrooklynMementoPersisterToObjectStore(
                objectStore,
                ((ManagementContextInternal)managementContext).getBrooklynProperties(),
                managementContext.getCatalogClassLoader());

        RebindManager rebindManager = managementContext.getRebindManager();

        PersistenceExceptionHandler persistenceExceptionHandler = PersistenceExceptionHandlerImpl.builder().build();
        ((RebindManagerImpl) rebindManager).setPeriodicPersistPeriod(Duration.ONE_SECOND);
        rebindManager.setPersister(persister, persistenceExceptionHandler);
    }

    //TODO test transform() - must change the memento
    @Test
    public void testSelectionWithOrphanedLocationsInData() {

        Set<String> locationsToKeep = MutableSet.of(
                "tjdywoxbji",
                "pudtixbw89"
        );

        Set<String> orphanedLocations = MutableSet.of(
                "banby1jx4j",
                "msyp655po0",
                "ppamsemxgo"
        );

        initManagementContextAndPersistence(persistenceDirWithOrphanedLocations);
        BrooklynMementoRawData mementoRawData = managementContext.getRebindManager().retrieveMementoRawData();

        Set<String> allReferencedLocationsFoundByTransformer = transformer.findAllReferencedLocations(mementoRawData);
        Map<String, String> locationsToKeepFoundByTransformer = transformer.findLocationsToKeep(mementoRawData);

        Asserts.assertEquals(allReferencedLocationsFoundByTransformer, locationsToKeep);
        Asserts.assertEquals(locationsToKeepFoundByTransformer.keySet(), locationsToKeep);

        Map<String, String> locationsNotToKeepDueToTransormer = MutableMap.of();
        locationsNotToKeepDueToTransormer.putAll(mementoRawData.getLocations());
        for (Map.Entry location: locationsToKeepFoundByTransformer.entrySet()) {
            locationsNotToKeepDueToTransormer.remove(location.getKey());
        }
        Set<String> notReferencedLocationsDueToTransormer = MutableSet.of();
        notReferencedLocationsDueToTransormer.addAll(mementoRawData.getLocations().keySet());
        for (String location: allReferencedLocationsFoundByTransformer) {
            notReferencedLocationsDueToTransormer.remove(location);
        }

        Asserts.assertEquals(notReferencedLocationsDueToTransormer, orphanedLocations);
        Asserts.assertEquals(locationsNotToKeepDueToTransormer.keySet(), orphanedLocations);


        BrooklynMementoRawData transformedMemento = transformer.transform(mementoRawData);
        Asserts.assertFalse(EqualsBuilder.reflectionEquals(mementoRawData, transformedMemento));
    }

    @Test
    public void testSelectionWithoutOrphanedLocationsInData() {

        Set<String> locationsToKeep = MutableSet.of(
                "tjdywoxbji",
                "pudtixbw89"
        );

        initManagementContextAndPersistence(persistenceDirWithoutOrphanedLocations);
        BrooklynMementoRawData mementoRawData = managementContext.getRebindManager().retrieveMementoRawData();

        Set<String> allReferencedLocationsFoundByTransformer = transformer.findAllReferencedLocations(mementoRawData);
        Map<String, String> locationsToKeepFoundByTransformer = transformer.findLocationsToKeep(mementoRawData);

        Asserts.assertEquals(allReferencedLocationsFoundByTransformer, locationsToKeep);
        Asserts.assertEquals(locationsToKeepFoundByTransformer.keySet(), locationsToKeep);

        Map<String, String> locationsNotToKeepDueToTransormer = MutableMap.of();
        locationsNotToKeepDueToTransormer.putAll(mementoRawData.getLocations());
        for (Map.Entry location: locationsToKeepFoundByTransformer.entrySet()) {
            locationsNotToKeepDueToTransormer.remove(location.getKey());
        }
        Set<String> notReferencedLocationsDueToTransormer = MutableSet.of();
        notReferencedLocationsDueToTransormer.addAll(mementoRawData.getLocations().keySet());
        for (String location: allReferencedLocationsFoundByTransformer) {
            notReferencedLocationsDueToTransormer.remove(location);
        }

        Asserts.assertSize(locationsNotToKeepDueToTransormer.keySet(), 0);
        Asserts.assertSize(notReferencedLocationsDueToTransormer, 0);

        BrooklynMementoRawData transformedMemento = transformer.transform(mementoRawData);
        Asserts.assertTrue(EqualsBuilder.reflectionEquals(mementoRawData, transformedMemento));
    }

    //TODO
    @Test
    public void testCopiedPersistedState() {
        launcherWithoutOrphanedLocations.cleanOrphanedLocations(destinationDir, destinationLocation);
        launcherWithOrphanedLocations.cleanOrphanedLocations(destinationDir, destinationLocation);
    }

    @Test
    public void testMultipleLocationOccurrenceInEntity() {
        Set<String> allReferencedLocations = MutableSet.of(
                "m72nvkl799",
                "m72nTYl799",
                "m72LKVN799",
                "jf4rwubqyb",
                "jf4rwuTTTb",
                "jf4rwuHHHb",
                "m72nvkl799",
                "m72nPTUF99",
                "m72nhtDr99"
        );

        Set<String> locationsToKeep = MutableSet.of(
                "m72nvkl799",
                "jf4rwubqyb"
        );

        initManagementContextAndPersistence(persistenceDirWithMultipleLocationsOccurrence);

        BrooklynMementoRawData mementoRawData = managementContext.getRebindManager().retrieveMementoRawData();
        Set<String> allReferencedLocationsFoundByTransformer = transformer.findAllReferencedLocations(mementoRawData);
        Map<String, String> locationsToKeepFoundByTransformer = transformer.findLocationsToKeep(mementoRawData);

        Asserts.assertEquals(allReferencedLocationsFoundByTransformer, allReferencedLocations);
        Asserts.assertEquals(locationsToKeepFoundByTransformer.keySet(), locationsToKeep);
    }

    @AfterMethod
    public void cleanCopiedPersistedState() {

    }
}
