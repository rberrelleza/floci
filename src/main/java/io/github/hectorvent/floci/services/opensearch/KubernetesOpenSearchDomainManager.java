package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadLauncher;
import io.github.hectorvent.floci.core.common.kubernetes.KubernetesWorkloadSpec;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one persistent, single-node OpenSearch workload per domain. Kubernetes workloads remain
 * running when Floci shuts down so subsequent startup can adopt their StatefulSets and PVCs.
 */
@ApplicationScoped
@Typed(KubernetesOpenSearchDomainManager.class)
public class KubernetesOpenSearchDomainManager implements OpenSearchRuntime {

    private static final Logger LOG = Logger.getLogger(KubernetesOpenSearchDomainManager.class);
    private static final int PORT = 9200;
    private static final String DATA_PATH = "/usr/share/opensearch/data";

    private final KubernetesWorkloadLauncher workloadLauncher;
    private final EmulatorConfig config;
    private final Map<String, String> activeWorkloads = new ConcurrentHashMap<>();

    @Inject
    public KubernetesOpenSearchDomainManager(KubernetesWorkloadLauncher workloadLauncher,
                                              EmulatorConfig config) {
        this.workloadLauncher = workloadLauncher;
        this.config = config;
    }

    public KubernetesOpenSearchDomainManager(KubernetesWorkloadLauncher workloadLauncher) {
        this(workloadLauncher, null);
    }

    @Override
    public void startDomain(Domain domain) {
        var workloadName = workloadName(domain);
        var image = OpenSearchVersions.resolveImage(
                config.services().opensearch().defaultImage(), domain.getEngineVersion());
        LOG.infov("Starting OpenSearch Kubernetes workload for domain {0}", domain.getDomainName());
        var builder = KubernetesWorkloadSpec.builder(
                        workloadName, "opensearch", domain.getDomainName(), image)
                .withPort(PORT)
                .withEnv("discovery.type", "single-node")
                .withStorage("", DATA_PATH, "data")
                .withFsGroup(1000);
        if (domain.getEngineVersion() == null
                || !domain.getEngineVersion().startsWith("Elasticsearch")) {
            builder.withEnv("DISABLE_SECURITY_PLUGIN", "true")
                    .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m");
            if (requiresInitialAdminPassword(domain.getEngineVersion())) {
                builder.withEnv("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "FlociAdmin1!");
            }
        }
        try {
            workloadLauncher.launch(builder.build(), false);
            domain.setContainerId(workloadName);
            domain.setEndpoint("http://" + workloadName + ":" + PORT);
            activeWorkloads.put(domain.getDomainName(), workloadName);
        } catch (RuntimeException | Error failure) {
            try {
                workloadLauncher.delete(workloadName, true);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public boolean isReady(Domain domain) {
        var workloadName = workloadName(domain);
        var url = "http://" + workloadName + ":" + PORT + "/_cluster/health";
        try {
            var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(2_000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            var body = new String(connection.getInputStream().readAllBytes());
            return body.contains("\"green\"") || body.contains("\"yellow\"");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void stopDomain(Domain domain) {
        var workloadName = workloadName(domain);
        activeWorkloads.remove(domain.getDomainName());
        workloadLauncher.delete(workloadName, false);
    }

    @Override
    public void removeDomainStorage(Domain domain) {
        workloadLauncher.delete(workloadName(domain), true);
    }

    @Override
    public void stopAll() {
        var count = activeWorkloads.size();
        activeWorkloads.clear();
        if (count > 0) {
            LOG.infov("Leaving {0} OpenSearch Kubernetes workload(s) running across Floci shutdown", count);
        }
    }

    private String workloadName(Domain domain) {
        var storageName = config == null
                ? "floci-opensearch-" + (domain.getVolumeId() != null
                ? domain.getVolumeId() : domain.getDomainName())
                : ContainerStorageHelper.resourceName(
                        config, "opensearch", domain.getVolumeId(), domain.getDomainName());
        return KubernetesWorkloadLauncher.sanitizeName(storageName);
    }

    private static boolean requiresInitialAdminPassword(String engineVersion) {
        if (engineVersion == null || !engineVersion.startsWith("OpenSearch_")) {
            return false;
        }
        var numeric = engineVersion.substring("OpenSearch_".length());
        var dot = numeric.indexOf('.');
        if (dot < 0) {
            return false;
        }
        try {
            var major = Integer.parseInt(numeric.substring(0, dot));
            var minor = Integer.parseInt(numeric.substring(dot + 1));
            return major > 2 || (major == 2 && minor >= 12);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
