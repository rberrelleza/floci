package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

/**
 * Runtime abstraction used by RDS for Docker and Kubernetes database workloads.
 */
public interface RdsContainerRuntime {

    RdsContainerHandle start(String instanceId, String volumeId, DatabaseEngine engine,
                             String image, String masterUsername,
                             String masterPassword, String dbName);

    RdsContainerHandle start(String runtimeId, String instanceId, String containerStorageResourceId,
                             String dockerVolumeName, DatabaseEngine engine, String image,
                             String masterUsername, String masterPassword, String dbName);

    default RdsContainerHandle start(String runtimeId, String instanceId, String containerStorageResourceId,
                                     String dockerVolumeName, DatabaseEngine engine, String image,
                                     String masterUsername, String masterPassword, String dbName,
                                     boolean iamEnabled) {
        return start(runtimeId, instanceId, containerStorageResourceId, dockerVolumeName, engine, image,
                masterUsername, masterPassword, dbName);
    }

    void stop(RdsContainerHandle handle);

    void stopByRuntimeId(String runtimeId);

    RdsContainerHandle getActiveHandle(String runtimeId);

    void stopAll();

    void removeVolume(String instanceId, String volumeId);

    void removeVolume(String runtimeId, String containerStorageResourceId, String dockerVolumeName);
}
