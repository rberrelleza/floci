package io.github.hectorvent.floci.core.common.kubernetes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of one Floci-managed Kubernetes workload.
 *
 * <p>Environment entries use the same {@code NAME=value} representation as the
 * Docker launcher, while the launcher translates them to Kubernetes EnvVars.</p>
 */
public record KubernetesWorkloadSpec(
        String name,
        String awsService,
        String resourceId,
        String image,
        List<Integer> ports,
        List<String> environment,
        List<String> command,
        List<String> args,
        Integer memoryMb,
        Optional<Storage> storage,
        Optional<Long> fsGroup,
        Map<String, String> labels,
        Map<String, String> annotations) {

    public KubernetesWorkloadSpec {
        name = require(name, "name");
        awsService = require(awsService, "awsService");
        resourceId = require(resourceId, "resourceId");
        image = require(image, "image");
        ports = immutableList(ports);
        environment = immutableList(environment);
        command = immutableList(command);
        args = immutableList(args);
        storage = storage == null ? Optional.empty() : storage;
        fsGroup = fsGroup == null ? Optional.empty() : fsGroup;
        labels = immutableMap(labels);
        annotations = immutableMap(annotations);
        if (memoryMb != null && memoryMb <= 0) {
            throw new IllegalArgumentException("memoryMb must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(String name, String awsService, String resourceId, String image) {
        return new Builder().withName(name).withAwsService(awsService).withResourceId(resourceId).withImage(image);
    }

    public static Builder builder(String awsService, String resourceId, String image) {
        return builder(KubernetesWorkloadLauncher.workloadName(awsService, resourceId),
                awsService, resourceId, image);
    }

    public record Storage(String size, String mountPath, Optional<String> subPath) {
        public Storage {
            size = require(size, "size");
            mountPath = require(mountPath, "mountPath");
            subPath = subPath == null ? Optional.empty() : subPath;
        }

        public Storage(String size, String mountPath, String subPath) {
            this(size, mountPath, Optional.ofNullable(subPath).filter(value -> !value.isBlank()));
        }
    }

    public static final class Builder {
        private String name;
        private String awsService;
        private String resourceId;
        private String image;
        private final List<Integer> ports = new ArrayList<>();
        private final List<String> environment = new ArrayList<>();
        private final List<String> command = new ArrayList<>();
        private final List<String> args = new ArrayList<>();
        private Integer memoryMb;
        private Optional<Storage> storage = Optional.empty();
        private Optional<Long> fsGroup = Optional.empty();
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, String> annotations = new LinkedHashMap<>();

        public Builder withName(String name) {
            this.name = name;
            return this;
        }

        public Builder withAwsService(String awsService) {
            this.awsService = awsService;
            return this;
        }

        public Builder withResourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder withImage(String image) {
            this.image = image;
            return this;
        }

        public Builder withPort(int port) {
            this.ports.add(port);
            return this;
        }

        public Builder withPorts(Iterable<Integer> ports) {
            ports.forEach(this.ports::add);
            return this;
        }

        public Builder withEnv(String name, String value) {
            this.environment.add(name + "=" + value);
            return this;
        }

        public Builder withEnvironment(Iterable<String> environment) {
            environment.forEach(this.environment::add);
            return this;
        }

        public Builder withCommand(Iterable<String> command) {
            command.forEach(this.command::add);
            return this;
        }

        public Builder withCmd(Iterable<String> command) {
            return withCommand(command);
        }

        public Builder withCmd(String command) {
            this.command.add(command);
            return this;
        }

        public Builder withArgs(Iterable<String> args) {
            args.forEach(this.args::add);
            return this;
        }

        public Builder withMemoryMb(int memoryMb) {
            this.memoryMb = memoryMb;
            return this;
        }

        public Builder withStorage(String size, String mountPath, String subPath) {
            this.storage = Optional.of(new Storage(size, mountPath, subPath));
            return this;
        }

        public Builder withStorage(Storage storage) {
            this.storage = Optional.ofNullable(storage);
            return this;
        }

        public Builder withFsGroup(long fsGroup) {
            this.fsGroup = Optional.of(fsGroup);
            return this;
        }

        public Builder withLabel(String key, String value) {
            labels.put(key, value);
            return this;
        }

        public Builder withLabels(Map<String, String> labels) {
            this.labels.putAll(labels);
            return this;
        }

        public Builder withAnnotation(String key, String value) {
            annotations.put(key, value);
            return this;
        }

        public Builder withAnnotations(Map<String, String> annotations) {
            this.annotations.putAll(annotations);
            return this;
        }

        public KubernetesWorkloadSpec build() {
            return new KubernetesWorkloadSpec(name, awsService, resourceId, image, ports, environment,
                    command, args, memoryMb, storage, fsGroup, labels, annotations);
        }
    }

    private static String require(String value, String field) {
        return Objects.requireNonNull(value, field + " must not be null");
    }

    private static <T> List<T> immutableList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> Map<String, T> immutableMap(Map<String, T> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }
}
