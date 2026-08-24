package io.github.hectorvent.floci.services.elasticache.container;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class ElastiCacheBackendProbe {

    private static final Logger LOG = Logger.getLogger(ElastiCacheBackendProbe.class);
    private static final int DEADLINE_MS = 60_000;
    private static final int RETRY_MS = 100;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final byte[] RESP_PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MEMCACHED_VERSION = "version\r\n".getBytes(StandardCharsets.UTF_8);

    public void waitForValkey(String groupId, String host, int port) {
        waitFor(groupId, host, port, RESP_PING, line -> line.startsWith("+PONG"), "ElastiCache");
    }

    public void waitForMemcached(String clusterId, String host, int port) {
        waitFor(clusterId, host, port, MEMCACHED_VERSION, line -> line.startsWith("VERSION"), "Memcached");
    }

    private void waitFor(String resourceId, String host, int port, byte[] request,
                         java.util.function.Predicate<String> response, String service) {
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                OutputStream output = socket.getOutputStream();
                output.write(request);
                output.flush();
                if (response.test(readAsciiLineCrLf(socket.getInputStream()))) {
                    return;
                }
            } catch (IOException exception) {
                LOG.debugv("Probe for {0} backend {1} failed: {2}", service, resourceId, exception.getMessage());
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for " + service + " backend " + resourceId,
                        exception);
            }
        }
        throw new RuntimeException(service + " backend for " + resourceId + " did not become ready on "
                + host + ":" + port + " within " + DEADLINE_MS + "ms");
    }

    private static String readAsciiLineCrLf(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Expected \\n after \\r in RESP line");
                }
                break;
            }
            result.append((char) value);
        }
        return result.toString();
    }
}
