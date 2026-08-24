package io.github.hectorvent.floci.core.common.kubernetes;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpecBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.VolumeResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetPersistentVolumeClaimRetentionPolicyBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class KubernetesWorkloadLauncher {

    private static final Logger LOG = Logger.getLogger(KubernetesWorkloadLauncher.class);
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    private static final String FLOCI_MANAGED_BY = "floci";
    private static final String SERVICE_LABEL = "floci.io/service";
    private static final String RESOURCE_LABEL = "floci.io/resource-id";
    private static final String WORKLOAD_CONTAINER = "workload";
    private static final String DATA_VOLUME = "data";

    private final KubernetesClient client;
    private final EmulatorConfig config;
    private final KubernetesNamespaceResolver namespaceResolver;

    @Inject
    public KubernetesWorkloadLauncher(KubernetesClient client,
                                      EmulatorConfig config,
                                      KubernetesNamespaceResolver namespaceResolver) {
        this.client = client;
        this.config = config;
        this.namespaceResolver = namespaceResolver;
    }

    public KubernetesWorkloadHandle launch(KubernetesWorkloadSpec spec) {
        return launch(spec, true);
    }

    public KubernetesWorkloadHandle launch(KubernetesWorkloadSpec spec, boolean waitForReady) {
        var namespace = namespaceResolver.resolve();
        var name = sanitizeName(spec.name());
        var labels = labels(spec);
        var selector = selector(spec);
        var existing = client.apps().statefulSets().inNamespace(namespace).withName(name).get();

        ensureService(namespace, name, labels, selector, spec.annotations(), spec.ports());
        if (existing == null) {
            var statefulSet = statefulSet(name, labels, selector, spec);
            client.apps().statefulSets().inNamespace(namespace).resource(statefulSet).create();
            LOG.infov("Created Kubernetes workload {0} in namespace {1}", name, namespace);
        } else {
            LOG.infov("Adopting existing Kubernetes workload {0} in namespace {1}", name, namespace);
        }

        if (waitForReady) {
            awaitReady(namespace, name);
        }
        return new KubernetesWorkloadHandle(name, namespace, name, name + "-0");
    }

    public void delete(String workloadName, boolean deleteStorage) {
        var namespace = namespaceResolver.resolve();
        var name = sanitizeName(workloadName);
        deleteAndAwait(client.apps().statefulSets().inNamespace(namespace).withName(name)::delete,
                () -> client.apps().statefulSets().inNamespace(namespace).withName(name).get());
        deleteAndAwait(client.services().inNamespace(namespace).withName(name)::delete,
                () -> client.services().inNamespace(namespace).withName(name).get());
        if (deleteStorage) {
            var pvc = DATA_VOLUME + "-" + name + "-0";
            deleteAndAwait(client.persistentVolumeClaims().inNamespace(namespace).withName(pvc)::delete,
                    () -> client.persistentVolumeClaims().inNamespace(namespace).withName(pvc).get());
        }
    }

    public boolean isAlive(String workloadName) {
        return find(workloadName) != null;
    }

    public StatefulSet find(String workloadName) {
        var namespace = namespaceResolver.resolve();
        return client.apps().statefulSets().inNamespace(namespace)
                .withName(sanitizeName(workloadName)).get();
    }

    public ExecWatch exec(String workloadName, String containerName, String... command) {
        var namespace = namespaceResolver.resolve();
        var pod = client.pods().inNamespace(namespace).withName(sanitizeName(workloadName) + "-0");
        return pod.inContainer(containerName)
                .redirectingOutput()
                .redirectingError()
                .exec(command);
    }

    public LogWatch logs(String workloadName, String containerName) {
        var namespace = namespaceResolver.resolve();
        var pod = client.pods().inNamespace(namespace).withName(sanitizeName(workloadName) + "-0");
        return pod.inContainer(containerName).watchLog();
    }

    public LogWatch logs(String workloadName) {
        return logs(workloadName, WORKLOAD_CONTAINER);
    }

    public static String workloadName(String awsService, String resourceId) {
        return sanitizeName("floci-" + awsService + "-" + resourceId);
    }

    public static String sanitizeName(String value) {
        var sanitized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (sanitized.isEmpty()) {
            sanitized = "floci-workload";
        }
        if (sanitized.length() > 63) {
            var hash = hashSuffix(value);
            sanitized = sanitized.substring(0, 63 - hash.length() - 1).replaceAll("-+$", "")
                    + "-" + hash;
        }
        return sanitized;
    }

    private static String hashSuffix(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(8);
            for (var index = 0; index < 4; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for Kubernetes workload names", exception);
        }
    }

    private void ensureService(String namespace, String name, Map<String, String> labels,
                               Map<String, String> selector, Map<String, String> annotations,
                               List<Integer> ports) {
        var services = client.services().inNamespace(namespace);
        if (services.withName(name).get() != null) {
            return;
        }
        var service = new ServiceBuilder()
                .withMetadata(metadata(name, labels, annotations))
                .withSpec(new ServiceSpecBuilder()
                        .withClusterIP("None")
                        .withPublishNotReadyAddresses(true)
                        .withSelector(selector)
                        .withPorts(servicePorts(ports))
                        .build())
                .build();
        try {
            services.resource(service).create();
        } catch (KubernetesClientException exception) {
            // Another Floci request may have won the create race. A conflict is safe to adopt.
            if (services.withName(name).get() == null) {
                throw exception;
            }
        }
    }

    private StatefulSet statefulSet(String name, Map<String, String> labels,
                                    Map<String, String> selector, KubernetesWorkloadSpec spec) {
        var container = container(spec);
        var podSpecBuilder = new PodSpecBuilder().withContainers(container);
        spec.fsGroup().ifPresent(group -> podSpecBuilder.withSecurityContext(
                new PodSecurityContextBuilder().withFsGroup(group).build()));
        var template = new PodTemplateSpecBuilder()
                .withMetadata(metadata(name, labels, spec.annotations()))
                .withSpec(podSpecBuilder.build())
                .build();

        var statefulSetSpecBuilder = new StatefulSetSpecBuilder()
                .withReplicas(1)
                .withServiceName(name)
                .withSelector(new LabelSelectorBuilder().withMatchLabels(selector).build())
                .withTemplate(template)
                .withPersistentVolumeClaimRetentionPolicy(
                        new StatefulSetPersistentVolumeClaimRetentionPolicyBuilder()
                                .withWhenDeleted("Retain")
                                .withWhenScaled("Retain")
                                .build());
        spec.storage().ifPresent(storage -> statefulSetSpecBuilder.withVolumeClaimTemplates(
                List.of(volumeClaim(labels, spec.annotations(), storage))));
        return new StatefulSetBuilder()
                .withMetadata(metadata(name, labels, spec.annotations()))
                .withSpec(statefulSetSpecBuilder.build())
                .build();
    }

    private Container container(KubernetesWorkloadSpec spec) {
        var builder = new ContainerBuilder()
                .withName(WORKLOAD_CONTAINER)
                .withImage(spec.image())
                .withImagePullPolicy(config.kubernetes().imagePullPolicy())
                .withSecurityContext(new SecurityContextBuilder()
                        .withPrivileged(false)
                        .withAllowPrivilegeEscalation(false)
                        .withSeccompProfile(new SeccompProfileBuilder()
                                .withType("RuntimeDefault")
                                .build())
                        .build())
                .withPorts(containerPorts(spec.ports()))
                .withEnv(environment(spec.environment()));
        if (!spec.command().isEmpty()) {
            builder.withCommand(spec.command());
        }
        if (!spec.args().isEmpty()) {
            builder.withArgs(spec.args());
        }
        if (spec.memoryMb() != null) {
            var memory = spec.memoryMb();
            builder.withResources(new ResourceRequirementsBuilder()
                    .withRequests(Map.of("memory", new Quantity(memory + "Mi")))
                    .withLimits(Map.of("memory", new Quantity(memory + "Mi")))
                    .build());
        }
        spec.storage().ifPresent(storage -> builder.withVolumeMounts(List.of(volumeMount(storage))));
        return builder.build();
    }

    private PersistentVolumeClaim volumeClaim(Map<String, String> labels, Map<String, String> annotations,
                                              KubernetesWorkloadSpec.Storage storage) {
        var size = storage.size().isBlank() ? config.kubernetes().defaultStorageSize() : storage.size();
        var pvcSpecBuilder = new PersistentVolumeClaimSpecBuilder()
                .withAccessModes("ReadWriteOnce")
                .withResources(new VolumeResourceRequirementsBuilder()
                        .withRequests(Map.of("storage", new Quantity(size)))
                        .build());
        config.kubernetes().storageClass()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .ifPresent(pvcSpecBuilder::withStorageClassName);
        return new PersistentVolumeClaimBuilder()
                .withMetadata(metadata(DATA_VOLUME, labels, annotations))
                .withSpec(pvcSpecBuilder.build())
                .build();
    }

    private void awaitReady(String namespace, String name) {
        var timeout = config.kubernetes().startupTimeoutSeconds();
        var podName = name + "-0";
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            var pod = client.pods().inNamespace(namespace).withName(podName).get();
            if (KubernetesPodReadiness.isReady(pod)) {
                return;
            }
            if (KubernetesPodReadiness.hasTerminalFailure(pod)) {
                throw new RuntimeException("Pod " + podName + " for workload '" + name
                        + "' failed to become Ready: " + KubernetesPodReadiness.describeFailure(pod));
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for pod " + podName + " to become Ready",
                        exception);
            }
        }
        var pod = client.pods().inNamespace(namespace).withName(podName).get();
        throw new RuntimeException("Pod " + podName + " for workload '" + name
                + "' failed to become Ready within " + timeout + "s: "
                + KubernetesPodReadiness.describeFailure(pod));
    }

    private static void deleteAndAwait(Operation delete, ObjectSupplier<?> get) {
        try {
            delete.run();
        } catch (KubernetesClientException exception) {
            // A missing object is already in the requested state.
            if (get.get() != null) {
                throw exception;
            }
            return;
        }
        for (var attempt = 0; attempt < 50; attempt++) {
            if (get.get() == null) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @FunctionalInterface
    private interface ObjectSupplier<T> {
        T get();
    }

    @FunctionalInterface
    private interface Operation {
        void run();
    }

    private Map<String, String> labels(KubernetesWorkloadSpec spec) {
        var labels = new LinkedHashMap<String, String>();
        putExtraLabels(labels, spec.labels());
        putExtraLabels(labels, extraLabels());
        labels.put(MANAGED_BY, FLOCI_MANAGED_BY);
        labels.put(SERVICE_LABEL, labelValue(spec.awsService()));
        labels.put(RESOURCE_LABEL, labelValue(spec.resourceId()));
        return labels;
    }

    private static void putExtraLabels(Map<String, String> labels, Map<String, String> extraLabels) {
        extraLabels.forEach((key, value) -> {
            if (MANAGED_BY.equals(key) || SERVICE_LABEL.equals(key) || RESOURCE_LABEL.equals(key)) {
                LOG.warnv("Ignoring Kubernetes label override for reserved key {0}", key);
                return;
            }
            labels.put(key, value);
        });
    }

    private Map<String, String> selector(KubernetesWorkloadSpec spec) {
        var selector = new LinkedHashMap<String, String>();
        selector.put(MANAGED_BY, FLOCI_MANAGED_BY);
        selector.put(SERVICE_LABEL, labelValue(spec.awsService()));
        selector.put(RESOURCE_LABEL, labelValue(spec.resourceId()));
        return selector;
    }

    private Map<String, String> extraLabels() {
        var labels = new LinkedHashMap<String, String>();
        config.kubernetes().labels().orElse(List.of()).forEach(entry -> {
            var separator = entry.indexOf('=');
            if (separator > 0) {
                labels.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
            }
        });
        return labels;
    }

    private static ObjectMeta metadata(String name, Map<String, String> labels, Map<String, String> annotations) {
        return new ObjectMetaBuilder().withName(name).withLabels(labels).withAnnotations(annotations).build();
    }

    private static String labelValue(String value) {
        var result = value.replaceAll("[^A-Za-z0-9_.-]", "-");
        return result.length() > 63 ? result.substring(0, 63) : result;
    }

    private static List<ContainerPort> containerPorts(List<Integer> ports) {
        return ports.stream().map(port -> new ContainerPortBuilder()
                .withName("port-" + port)
                .withContainerPort(port)
                .build()).toList();
    }

    private static List<ServicePort> servicePorts(List<Integer> ports) {
        return ports.stream().map(port -> new ServicePortBuilder()
                .withName("port-" + port)
                .withPort(port)
                .withTargetPort(new IntOrString(port))
                .build()).toList();
    }

    private static List<EnvVar> environment(List<String> environment) {
        return environment.stream().map(entry -> {
            var separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Environment entry must be NAME=value: " + entry);
            }
            return new EnvVarBuilder().withName(entry.substring(0, separator))
                    .withValue(entry.substring(separator + 1)).build();
        }).toList();
    }

    private static VolumeMount volumeMount(KubernetesWorkloadSpec.Storage storage) {
        var builder = new VolumeMountBuilder().withName(DATA_VOLUME).withMountPath(storage.mountPath());
        storage.subPath().ifPresent(builder::withSubPath);
        return builder.build();
    }

    public record KubernetesWorkloadHandle(String name, String namespace, String serviceName, String podName) {
    }
}
