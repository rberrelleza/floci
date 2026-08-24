package io.github.hectorvent.floci.core.common.kubernetes;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodConditionBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.VolumeResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnableKubernetesMockClient(crud = true)
class KubernetesWorkloadLauncherTest {

    KubernetesClient client;

    private EmulatorConfig config;
    private EmulatorConfig.KubernetesConfig kubernetes;
    private KubernetesNamespaceResolver namespaceResolver;
    private KubernetesWorkloadLauncher launcher;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class);
        kubernetes = mock(EmulatorConfig.KubernetesConfig.class);
        namespaceResolver = mock(KubernetesNamespaceResolver.class);
        when(config.kubernetes()).thenReturn(kubernetes);
        when(namespaceResolver.resolve()).thenReturn("default");
        when(kubernetes.storageClass()).thenReturn(Optional.of("fast"));
        when(kubernetes.labels()).thenReturn(Optional.of(List.of("team=platform")));
        when(kubernetes.defaultStorageSize()).thenReturn("5Gi");
        when(kubernetes.imagePullPolicy()).thenReturn("IfNotPresent");
        when(kubernetes.startupTimeoutSeconds()).thenReturn(5);
        launcher = new KubernetesWorkloadLauncher(client, config, namespaceResolver);
    }

    @Test
    void createsHeadlessStatefulSetAndStorage() {
        var spec = spec().withStorage("10Gi", "/var/lib/data", "data")
                .withFsGroup(1001).withAnnotation("example.com/owner", "test").build();
        markPodReadyInBackground("floci-rds-resource-1");

        launcher.launch(spec);

        var service = client.services().inNamespace("default").withName("floci-rds-resource-1").get();
        assertThat(service.getSpec().getClusterIP()).isEqualTo("None");
        assertThat(service.getSpec().getSelector())
                .containsEntry("app.kubernetes.io/managed-by", "floci")
                .containsEntry("floci.io/service", "rds");
        assertThat(service.getMetadata().getAnnotations()).containsEntry("example.com/owner", "test");

        var statefulSet = client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get();
        assertThat(statefulSet.getSpec().getReplicas()).isEqualTo(1);
        assertThat(statefulSet.getSpec().getTemplate().getSpec().getSecurityContext().getFsGroup())
                .isEqualTo(1001L);
        var container = statefulSet.getSpec().getTemplate().getSpec().getContainers().getFirst();
        assertThat(container.getSecurityContext().getPrivileged()).isFalse();
        assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
        assertThat(container.getSecurityContext().getSeccompProfile().getType())
                .isEqualTo("RuntimeDefault");
        assertThat(container.getVolumeMounts().getFirst().getMountPath()).isEqualTo("/var/lib/data");
        assertThat(container.getVolumeMounts().getFirst().getSubPath()).isEqualTo("data");

        var pvc = statefulSet.getSpec().getVolumeClaimTemplates().getFirst();
        assertThat(pvc.getMetadata().getName()).isEqualTo("data");
        assertThat(pvc.getSpec().getStorageClassName()).isEqualTo("fast");
        assertThat(pvc.getSpec().getResources().getRequests().get("storage").toString()).isEqualTo("10Gi");
    }

    @Test
    void omitsStorageWhenSpecHasNoStorage() {
        var spec = spec().build();
        markPodReadyInBackground("floci-rds-resource-1");

        launcher.launch(spec);

        var statefulSet = client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get();
        assertThat(statefulSet.getSpec().getVolumeClaimTemplates()).isNullOrEmpty();
        assertThat(statefulSet.getSpec().getTemplate().getSpec().getContainers().getFirst().getVolumeMounts())
                .isNullOrEmpty();
    }

    @Test
    void canCreateOrAdoptWithoutWaitingForReadiness() {
        var spec = spec().build();

        var handle = launcher.launch(spec, false);

        assertThat(handle.name()).isEqualTo("floci-rds-resource-1");
        assertThat(client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get()).isNotNull();
        assertThat(client.pods().inNamespace("default")
                .withName("floci-rds-resource-1-0").get()).isNull();
    }

    @Test
    void adoptsExistingStatefulSetWithoutRecreatingIt() {
        var spec = spec().withStorage("10Gi", "/data", null).build();
        var existingPvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName("data-floci-rds-resource-1-0").endMetadata()
                .build();
        var pvcResourceVersion = client.persistentVolumeClaims().inNamespace("default")
                .resource(existingPvc).create().getMetadata().getResourceVersion();
        var existing = new StatefulSetBuilder()
                .withNewMetadata().withName("floci-rds-resource-1").endMetadata()
                .build();
        var created = client.apps().statefulSets().inNamespace("default").resource(existing).create();
        var resourceVersion = created.getMetadata().getResourceVersion();
        markPodReadyInBackground("floci-rds-resource-1");

        launcher.launch(spec);

        var adopted = client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get();
        assertThat(adopted.getMetadata().getResourceVersion()).isEqualTo(resourceVersion);
        assertThat(adopted.getSpec()).isNull();
        assertThat(client.persistentVolumeClaims().inNamespace("default")
                .withName("data-floci-rds-resource-1-0").get().getMetadata().getResourceVersion())
                .isEqualTo(pvcResourceVersion);
    }

    @Test
    void deleteRetainsOrRemovesResourcePvcAsRequested() {
        var pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata().withName("data-floci-rds-resource-1-0").endMetadata()
                .withSpec(new PersistentVolumeClaimSpecBuilder()
                        .withAccessModes("ReadWriteOnce")
                        .withResources(new VolumeResourceRequirementsBuilder()
                                .withRequests(Map.of("storage", new Quantity("5Gi"))).build())
                        .build())
                .build();
        client.persistentVolumeClaims().inNamespace("default").resource(pvc).create();
        client.apps().statefulSets().inNamespace("default").resource(
                new StatefulSetBuilder().withNewMetadata().withName("floci-rds-resource-1").endMetadata().build()).create();

        launcher.delete("floci-rds-resource-1", false);
        assertThat(client.persistentVolumeClaims().inNamespace("default")
                .withName("data-floci-rds-resource-1-0").get()).isNotNull();

        launcher.delete("floci-rds-resource-1", true);
        assertThat(client.persistentVolumeClaims().inNamespace("default")
                .withName("data-floci-rds-resource-1-0").get()).isNull();
    }

    @Test
    void deleteMissingWorkloadIsNoOp() {
        launcher.delete("does-not-exist", true);
    }

    @Test
    void readinessFailureIncludesEvictedReason() {
        markPodFailureInBackground("floci-rds-resource-1", "Evicted");

        assertThatThrownBy(() -> launcher.launch(spec().build()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Evicted");
    }

    @Test
    void sanitizesNamesAndBuildsDeterministicNames() {
        assertThat(KubernetesWorkloadLauncher.sanitizeName("Floci/RDS resource_1"))
                .isEqualTo("floci-rds-resource-1");
        assertThat(KubernetesWorkloadLauncher.workloadName("rds", "db/one"))
                .isEqualTo("floci-rds-db-one");
    }

    @Test
    void disambiguatesLongNamesThatWouldOtherwiseBeTruncated() {
        var first = "floci-rds-" + "a".repeat(60) + "-first";
        var second = "floci-rds-" + "a".repeat(60) + "-second";

        var firstSanitized = KubernetesWorkloadLauncher.sanitizeName(first);
        var secondSanitized = KubernetesWorkloadLauncher.sanitizeName(second);

        assertThat(firstSanitized).hasSizeLessThanOrEqualTo(63);
        assertThat(secondSanitized).hasSizeLessThanOrEqualTo(63);
        assertThat(firstSanitized).isNotEqualTo(secondSanitized);
        assertThat(firstSanitized).startsWith("floci-rds-" + "a".repeat(44) + "-");
        assertThat(firstSanitized).matches(".+-[0-9a-f]{8}");
        assertThat(secondSanitized).matches(".+-[0-9a-f]{8}");
    }

    @Test
    void keepsNamesWithinTheLimitByteIdentical() {
        var name = "floci-rds-short-resource";

        assertThat(KubernetesWorkloadLauncher.sanitizeName(name)).isEqualTo(name);
    }

    @Test
    void reportsExistingWorkloadForRestartAdoption() {
        var name = "floci-rds-existing";
        client.apps().statefulSets().inNamespace("default")
                .resource(new StatefulSetBuilder().withNewMetadata().withName(name).endMetadata().build())
                .create();

        assertThat(launcher.isAlive(name)).isTrue();
        assertThat(launcher.find(name).getMetadata().getName()).isEqualTo(name);
        assertThat(launcher.isAlive("floci-rds-missing")).isFalse();
    }

    @Test
    void reservedManagedLabelsAlwaysWin() {
        when(kubernetes.labels()).thenReturn(Optional.of(List.of(
                "app.kubernetes.io/managed-by=config",
                "floci.io/service=config",
                "floci.io/resource-id=config")));
        var spec = spec()
                .withLabel("app.kubernetes.io/managed-by", "user")
                .withLabel("floci.io/service", "user")
                .withLabel("floci.io/resource-id", "user")
                .build();
        markPodReadyInBackground("floci-rds-resource-1");

        launcher.launch(spec);

        var statefulSet = client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get();
        assertThat(statefulSet.getMetadata().getLabels())
                .containsEntry("app.kubernetes.io/managed-by", "floci")
                .containsEntry("floci.io/service", "rds")
                .containsEntry("floci.io/resource-id", "resource-1");
    }

    @Test
    void workloadContainerDoesNotUsePrivilegedOrEscalatedSecurityContext() {
        markPodReadyInBackground("floci-rds-resource-1");

        launcher.launch(spec().build());

        var podSpec = client.apps().statefulSets().inNamespace("default")
                .withName("floci-rds-resource-1").get().getSpec().getTemplate().getSpec();
        assertThat(podSpec.getSecurityContext()).isNull();
        assertThat(podSpec.getContainers().getFirst().getSecurityContext().getPrivileged()).isFalse();
        assertThat(podSpec.getContainers().getFirst().getSecurityContext()
                .getAllowPrivilegeEscalation()).isFalse();
        assertThat(podSpec.getContainers().getFirst().getSecurityContext()
                .getSeccompProfile().getType()).isEqualTo("RuntimeDefault");
    }

    private KubernetesWorkloadSpec.Builder spec() {
        return KubernetesWorkloadSpec.builder("floci-rds-resource-1", "rds", "resource-1", "postgres:16")
                .withPort(5432).withEnv("POSTGRES_DB", "app").withLabel("custom", "label");
    }

    private void markPodReadyInBackground(String name) {
        CompletableFuture.runAsync(() -> {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                    () -> client.apps().statefulSets().inNamespace("default").withName(name).get() != null);
            var pod = new PodBuilder().withNewMetadata().withName(name + "-0").endMetadata().build();
            client.pods().inNamespace("default").resource(pod).create();
            pod.setStatus(new PodStatusBuilder().withPhase("Running")
                    .withConditions(new PodConditionBuilder().withType("Ready").withStatus("True").build())
                    .build());
            client.pods().inNamespace("default").resource(pod).updateStatus();
        });
    }

    private void markPodFailureInBackground(String name, String reason) {
        CompletableFuture.runAsync(() -> {
            Awaitility.await().atMost(Duration.ofSeconds(5)).until(
                    () -> client.apps().statefulSets().inNamespace("default").withName(name).get() != null);
            var pod = new PodBuilder().withNewMetadata().withName(name + "-0").endMetadata().build();
            client.pods().inNamespace("default").resource(pod).create();
            pod.setStatus(new PodStatusBuilder().withPhase("Failed").withReason(reason)
                    .withMessage("node pressure").build());
            client.pods().inNamespace("default").resource(pod).updateStatus();
        });
    }
}
