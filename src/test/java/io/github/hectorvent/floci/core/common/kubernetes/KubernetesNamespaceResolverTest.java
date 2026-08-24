package io.github.hectorvent.floci.core.common.kubernetes;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubernetesNamespaceResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void prefersConfiguredNamespace() {
        var config = mock(EmulatorConfig.class);
        var kubernetes = mock(EmulatorConfig.KubernetesConfig.class);
        when(config.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.namespace()).thenReturn(Optional.of("team-a"));

        assertThat(new KubernetesNamespaceResolver(config).resolve()).isEqualTo("team-a");
    }

    @Test
    void fallsBackToServiceAccountNamespaceThenDefault() throws Exception {
        var config = mock(EmulatorConfig.class);
        var kubernetes = mock(EmulatorConfig.KubernetesConfig.class);
        when(config.kubernetes()).thenReturn(kubernetes);
        when(kubernetes.namespace()).thenReturn(Optional.empty());
        var namespaceFile = temporaryDirectory.resolve("namespace");
        java.nio.file.Files.writeString(namespaceFile, "team-b\n");

        assertThat(new KubernetesNamespaceResolver(config, namespaceFile).resolve()).isEqualTo("team-b");
        assertThat(new KubernetesNamespaceResolver(config, temporaryDirectory.resolve("missing")).resolve())
                .isEqualTo("default");
    }
}
