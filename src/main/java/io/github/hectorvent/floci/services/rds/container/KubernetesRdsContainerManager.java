package io.github.hectorvent.floci.services.rds.container;

import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs one Kubernetes StatefulSet-backed workload for each RDS instance or cluster.
 * Kubernetes workloads intentionally remain running when Floci shuts down; a later restore
 * adopts the existing StatefulSets and PVCs instead of recreating them.
 */
@ApplicationScoped
@Typed(KubernetesRdsContainerManager.class)
public class KubernetesRdsContainerManager implements RdsContainerRuntime {

    private static final Logger LOG = Logger.getLogger(KubernetesRdsContainerManager.class);
    private static final String WORKLOAD_CONTAINER = "workload";
    private static final String STORAGE_SUB_PATH = "data";
    private static final int POSTGRES_IAM_ATTEMPTS = 60;
    private static final long POSTGRES_IAM_RETRY_SECONDS = 1;

    private final KubernetesWorkloadLauncher workloadLauncher;
    private final EmulatorConfig config;
    private final Map<String, RdsContainerHandle> activeContainers = new ConcurrentHashMap<>();

    @Inject
    public KubernetesRdsContainerManager(KubernetesWorkloadLauncher workloadLauncher,
                                         EmulatorConfig config) {
        this.workloadLauncher = workloadLauncher;
        this.config = config;
    }

    public KubernetesRdsContainerManager(KubernetesWorkloadLauncher workloadLauncher) {
        this(workloadLauncher, null);
    }

    @Override
    public RdsContainerHandle start(String instanceId, String volumeId, DatabaseEngine engine,
                                    String image, String masterUsername,
                                    String masterPassword, String dbName) {
        var storageName = config == null
                ? KubernetesWorkloadLauncher.workloadName("rds",
                        volumeId != null && !volumeId.isBlank() ? volumeId : instanceId)
                : ContainerStorageHelper.resourceName(config, "rds", volumeId, instanceId);
        return start(instanceId, instanceId, instanceId, storageName,
                engine, image, masterUsername, masterPassword, dbName);
    }

    @Override
    public RdsContainerHandle start(String runtimeId, String instanceId, String containerStorageResourceId,
                                    String dockerVolumeName, DatabaseEngine engine, String image,
                                    String masterUsername, String masterPassword, String dbName) {
        return start(runtimeId, instanceId, containerStorageResourceId, dockerVolumeName, engine, image,
                masterUsername, masterPassword, dbName, true);
    }

    @Override
    public RdsContainerHandle start(String runtimeId, String instanceId, String containerStorageResourceId,
                                    String dockerVolumeName, DatabaseEngine engine, String image,
                                    String masterUsername, String masterPassword, String dbName,
                                    boolean iamEnabled) {
        var workloadName = workloadName(containerStorageResourceId, dockerVolumeName);
        var spec = KubernetesWorkloadSpec.builder(workloadName, "rds", instanceId, image)
                .withPort(engine.defaultPort())
                .withEnvironment(RdsEngineRuntime.environment(
                        engine, masterUsername, masterPassword, dbName))
                .withArgs(RdsEngineRuntime.command(engine))
                .withStorage("", RdsEngineRuntime.dataPath(engine, image), STORAGE_SUB_PATH)
                .withFsGroup(RdsEngineRuntime.fsGroup(engine))
                .build();

        LOG.infov("Starting RDS Kubernetes workload for instance {0} engine={1}", instanceId, engine);
        try {
            workloadLauncher.launch(spec);
            if (engine == DatabaseEngine.POSTGRES && iamEnabled) {
                initializePostgresIamRole(workloadName, masterUsername, masterPassword);
            }
        } catch (RuntimeException | Error exception) {
            try {
                workloadLauncher.delete(workloadName, false);
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        var handle = new RdsContainerHandle(
                workloadName, runtimeId, instanceId, workloadName, engine.defaultPort());
        activeContainers.put(handle.getRuntimeId(), handle);
        return handle;
    }

    @Override
    public void stop(RdsContainerHandle handle) {
        if (handle == null) {
            return;
        }
        var active = activeContainers.remove(handle.getRuntimeId());
        var effective = active != null ? active : handle;
        workloadLauncher.delete(effective.getContainerId(), false);
    }

    @Override
    public void stopByRuntimeId(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            return;
        }
        var handle = activeContainers.get(runtimeId);
        if (handle != null) {
            stop(handle);
        }
    }

    @Override
    public RdsContainerHandle getActiveHandle(String runtimeId) {
        return runtimeId == null || runtimeId.isBlank() ? null : activeContainers.get(runtimeId);
    }

    @Override
    public void stopAll() {
        int count = activeContainers.size();
        activeContainers.clear();
        if (count > 0) {
            LOG.infov("Leaving {0} RDS Kubernetes workload(s) running across Floci shutdown", count);
        }
    }

    @Override
    public void removeVolume(String instanceId, String volumeId) {
        var storageName = config == null
                ? KubernetesWorkloadLauncher.workloadName("rds",
                        volumeId != null && !volumeId.isBlank() ? volumeId : instanceId)
                : ContainerStorageHelper.resourceName(config, "rds", volumeId, instanceId);
        deleteStorage(instanceId, storageName);
    }

    @Override
    public void removeVolume(String runtimeId, String containerStorageResourceId,
                             String dockerVolumeName) {
        deleteStorage(containerStorageResourceId, dockerVolumeName);
    }

    public boolean isAlive(String containerStorageResourceId) {
        return isAlive(containerStorageResourceId, null);
    }

    public boolean isAlive(String containerStorageResourceId, String dockerVolumeName) {
        return workloadLauncher.isAlive(workloadName(containerStorageResourceId, dockerVolumeName));
    }

    private void deleteStorage(String storageResourceId, String dockerVolumeName) {
        workloadLauncher.delete(workloadName(storageResourceId, dockerVolumeName), true);
    }

    private static String workloadName(String storageResourceId, String dockerVolumeName) {
        var storageName = dockerVolumeName != null && !dockerVolumeName.isBlank()
                ? dockerVolumeName : storageResourceId;
        return KubernetesWorkloadLauncher.sanitizeName(storageName);
    }

    private void initializePostgresIamRole(String workloadName, String masterUsername, String masterPassword) {
        String effectiveUser = masterUsername != null && !masterUsername.isBlank()
                ? masterUsername : "postgres";
        String[] command = {
                "psql", "-v", "ON_ERROR_STOP=1", "-U", effectiveUser, "-d", "postgres",
                "-c", RdsEngineRuntime.postgresIamRoleInitSql()
        };
        String lastFailure = "";
        boolean execUnavailable = false;
        for (int attempt = 1; attempt <= POSTGRES_IAM_ATTEMPTS; attempt++) {
            if (!execUnavailable) {
                try (ExecWatch watch = workloadLauncher.exec(workloadName, WORKLOAD_CONTAINER, command)) {
                    var exitCode = watch.exitCode().get(5, TimeUnit.SECONDS);
                    if (exitCode == 0) {
                        LOG.infov("Initialized PostgreSQL IAM role in RDS workload {0}", workloadName);
                        return;
                    }
                    lastFailure = "exec exit code " + exitCode;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted initializing PostgreSQL IAM role in " + workloadName, exception);
                } catch (Exception exception) {
                    lastFailure = exception.getMessage();
                    execUnavailable = true;
                }
            }
            try {
                if (initializePostgresIamRoleOverConnection(workloadName, effectiveUser, masterPassword)) {
                    LOG.infov("Initialized PostgreSQL IAM role in RDS workload {0}", workloadName);
                    return;
                }
            } catch (SQLException exception) {
                lastFailure = exception.getMessage();
            }
            try {
                TimeUnit.SECONDS.sleep(POSTGRES_IAM_RETRY_SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted initializing PostgreSQL IAM role in " + workloadName, exception);
            }
        }
        throw new IllegalStateException(
                "Timed out initializing PostgreSQL IAM role in " + workloadName + ": " + lastFailure);
    }

    private boolean initializePostgresIamRoleOverConnection(String workloadName,
                                                              String username,
                                                              String password) throws SQLException {
        var url = "jdbc:postgresql://" + workloadName + ":" + DatabaseEngine.POSTGRES.defaultPort()
                + "/postgres?sslmode=disable&connectTimeout=5";
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.execute(RdsEngineRuntime.postgresIamRoleInitSql());
            return true;
        }
    }
}
