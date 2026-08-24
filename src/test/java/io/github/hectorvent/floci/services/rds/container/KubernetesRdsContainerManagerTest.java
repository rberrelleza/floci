package io.github.hectorvent.floci.services.rds.container;

import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KubernetesRdsContainerManagerTest {

    @Test
    void buildsPersistentWorkloadSpecForEachEngine() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var exec = mock(ExecWatch.class);
        when(exec.exitCode()).thenReturn(CompletableFuture.completedFuture(0));
        when(launcher.exec(any(), any(), any(String[].class))).thenReturn(exec);
        var manager = new KubernetesRdsContainerManager(launcher);

        for (var engine : DatabaseEngine.values()) {
            manager.start("arn:aws:rds:us-east-1:000000000000:db:db-" + engine,
                    "db-" + engine, "storage-" + engine,
                    "floci-rds-storage-" + engine, engine, image(engine),
                    "app", "password", "db");
        }

        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher, org.mockito.Mockito.times(3)).launch(captor.capture());
        var specs = captor.getAllValues();
        for (var engine : DatabaseEngine.values()) {
            var spec = specs.stream()
                    .filter(candidate -> candidate.resourceId().equals("db-" + engine))
                    .findFirst().orElseThrow();
            assertThat(spec.image()).isEqualTo(image(engine));
            assertThat(spec.ports()).containsExactly(engine.defaultPort());
            assertThat(spec.args()).containsExactlyElementsOf(RdsEngineRuntime.command(engine));
            assertThat(spec.storage()).get().satisfies(storage -> {
                assertThat(storage.mountPath()).isEqualTo(RdsEngineRuntime.dataPath(engine, image(engine)));
                assertThat(storage.subPath()).contains("data");
            });
            assertThat(spec.fsGroup()).contains(999L);
        }
        verify(launcher).exec(any(), any(), any(String[].class));
    }

    @Test
    void usesStorageIdentityForWorkloadAndDeletesPvcOnlyOnResourceDelete() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesRdsContainerManager(launcher);
        var storageIdentity = "cluster-ABC123";

        manager.start("runtime", "db", storageIdentity, "docker-volume",
                DatabaseEngine.MYSQL, "mysql:8", "root", "password", "db");
        manager.stop(new RdsContainerHandle(
                KubernetesWorkloadLauncher.sanitizeName("docker-volume"),
                "runtime", "db",
                KubernetesWorkloadLauncher.sanitizeName("docker-volume"), 3306));
        manager.removeVolume("runtime", storageIdentity, "docker-volume");

        var workloadName = KubernetesWorkloadLauncher.sanitizeName("docker-volume");
        verify(launcher).delete(workloadName, false);
        verify(launcher).delete(workloadName, true);
    }

    @Test
    void failedCreationDeletesWorkloadAndStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        when(launcher.hasPersistentVolumeClaim(
                KubernetesWorkloadLauncher.sanitizeName("docker-volume"))).thenReturn(false);
        when(launcher.launch(any())).thenThrow(new IllegalStateException("launch failed"));
        var manager = new KubernetesRdsContainerManager(launcher);

        assertThrows(IllegalStateException.class, () -> manager.start(
                "runtime", "db", "storage-id", "docker-volume",
                DatabaseEngine.MYSQL, "mysql:8", "root", "password", "db"));

        verify(launcher).delete(KubernetesWorkloadLauncher.sanitizeName("docker-volume"), true);
    }

    @Test
    void failedAdoptionRetainsExistingStorage() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var workloadName = KubernetesWorkloadLauncher.sanitizeName("docker-volume");
        when(launcher.hasPersistentVolumeClaim(workloadName)).thenReturn(true);
        when(launcher.launch(any())).thenThrow(new IllegalStateException("launch failed"));
        var manager = new KubernetesRdsContainerManager(launcher);

        assertThrows(IllegalStateException.class, () -> manager.start(
                "runtime", "db", "storage-id", "docker-volume",
                DatabaseEngine.MYSQL, "mysql:8", "root", "password", "db"));

        verify(launcher).delete(workloadName, false);
        verify(launcher, org.mockito.Mockito.never()).delete(workloadName, true);
    }

    @Test
    void legacyVolumeIdAndInstanceIdUseTheSameWorkloadName() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var config = mock(EmulatorConfig.class);
        var manager = new KubernetesRdsContainerManager(launcher, config);
        var rds = mock(EmulatorConfig.RdsServiceConfig.class);
        var services = mock(EmulatorConfig.ServicesConfig.class);
        var docker = mock(EmulatorConfig.DockerConfig.class);
        when(config.services()).thenReturn(services);
        when(services.rds()).thenReturn(rds);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.of("legacy"));

        manager.start("instance-id", "volume-id", DatabaseEngine.MYSQL,
                "mysql:8", "root", "password", "db");
        manager.removeVolume("instance-id", "volume-id");

        var storageName = ContainerStorageHelper.resourceName(
                config, "rds", "volume-id", "instance-id");
        verify(launcher).delete(KubernetesWorkloadLauncher.sanitizeName(storageName), true);
        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher).launch(captor.capture());
        assertThat(captor.getValue().name())
                .isEqualTo(KubernetesWorkloadLauncher.sanitizeName(storageName));
    }

    @Test
    void reusesStorageIdentityWhenBackendIsRecreated() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var first = new KubernetesRdsContainerManager(launcher);
        var second = new KubernetesRdsContainerManager(launcher);

        first.start("runtime-1", "db", "persisted-storage-id", "docker-volume",
                DatabaseEngine.MARIADB, "mariadb:11", "app", "password", "db");
        second.start("runtime-2", "db", "persisted-storage-id", "docker-volume",
                DatabaseEngine.MARIADB, "mariadb:11", "app", "password", "db");

        var captor = org.mockito.ArgumentCaptor.forClass(KubernetesWorkloadSpec.class);
        verify(launcher, org.mockito.Mockito.times(2)).launch(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(KubernetesWorkloadSpec::name)
                .containsExactly(
                        KubernetesWorkloadLauncher.sanitizeName("docker-volume"),
                KubernetesWorkloadLauncher.sanitizeName("docker-volume"));
    }

    @Test
    void shutdownLeavesRdsWorkloadRunning() {
        var launcher = mock(KubernetesWorkloadLauncher.class);
        var manager = new KubernetesRdsContainerManager(launcher);

        manager.start("runtime", "db", "persisted-storage-id", "docker-volume",
                DatabaseEngine.MARIADB, "mariadb:11", "app", "password", "db");
        manager.stopAll();

        verify(launcher, org.mockito.Mockito.never()).delete(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static String image(DatabaseEngine engine) {
        return switch (engine) {
            case POSTGRES -> "postgres:18";
            case MYSQL -> "mysql:8";
            case MARIADB -> "mariadb:11";
        };
    }
}
