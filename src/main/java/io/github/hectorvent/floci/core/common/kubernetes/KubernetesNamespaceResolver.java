package io.github.hectorvent.floci.core.common.kubernetes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class KubernetesNamespaceResolver {

    private static final Logger LOG = Logger.getLogger(KubernetesNamespaceResolver.class);
    static final Path SERVICE_ACCOUNT_NAMESPACE =
            Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");

    private final EmulatorConfig config;
    private final Path namespaceFile;

    @Inject
    public KubernetesNamespaceResolver(EmulatorConfig config) {
        this(config, SERVICE_ACCOUNT_NAMESPACE);
    }

    KubernetesNamespaceResolver(EmulatorConfig config, Path namespaceFile) {
        this.config = config;
        this.namespaceFile = namespaceFile;
    }

    public String resolve() {
        var configured = config.kubernetes().namespace()
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (configured.isPresent()) {
            return configured.get();
        }
        try {
            var serviceAccountNamespace = Files.readString(namespaceFile).trim();
            if (!serviceAccountNamespace.isEmpty()) {
                return serviceAccountNamespace;
            }
        } catch (IOException | SecurityException ignored) {
            LOG.debug("Kubernetes service-account namespace is unavailable; using default namespace");
        }
        return "default";
    }
}
