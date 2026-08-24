package io.github.hectorvent.floci.core.common.kubernetes;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

import java.util.ArrayList;
import java.util.Set;

public final class KubernetesPodReadiness {

    private static final Set<String> TERMINAL_WAITING_REASONS = Set.of(
            "ImagePullBackOff", "InvalidImageName", "CrashLoopBackOff",
            "CreateContainerError", "CreateContainerConfigError", "RunContainerError");

    private KubernetesPodReadiness() {
    }

    public static boolean isRunning(Pod pod) {
        return pod != null && pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase());
    }

    public static boolean isReady(Pod pod) {
        if (!isRunning(pod) || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
                .anyMatch(condition -> "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    }

    public static boolean hasTerminalFailure(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        return "Failed".equals(pod.getStatus().getPhase()) || "Succeeded".equals(pod.getStatus().getPhase())
                || unschedulableReason(pod) != null
                || describeTerminalReason(pod) != null;
    }

    public static String describeFailure(Pod pod) {
        if (pod == null || pod.getStatus() == null) {
            return "pod no longer exists";
        }
        var status = pod.getStatus();
        if (status.getReason() != null && !status.getReason().isBlank()) {
            return status.getReason();
        }
        var reason = describeTerminalReason(pod);
        if (reason == null) {
            reason = unschedulableReason(pod);
        }
        return reason == null ? "phase=" + status.getPhase() : reason;
    }

    public static String unschedulableReason(Pod pod) {
        if (pod.getStatus().getConditions() == null) {
            return null;
        }
        return pod.getStatus().getConditions().stream()
                .filter(condition -> "PodScheduled".equals(condition.getType())
                        && "False".equals(condition.getStatus())
                        && "Unschedulable".equals(condition.getReason()))
                .map(condition -> "Unschedulable: " + condition.getMessage())
                .findFirst()
                .orElse(null);
    }

    public static String describeTerminalReason(Pod pod) {
        var statuses = new ArrayList<ContainerStatus>();
        if (pod.getStatus().getInitContainerStatuses() != null) {
            statuses.addAll(pod.getStatus().getInitContainerStatuses());
        }
        if (pod.getStatus().getContainerStatuses() != null) {
            statuses.addAll(pod.getStatus().getContainerStatuses());
        }
        for (var status : statuses) {
            if (status.getState() != null && status.getState().getWaiting() != null
                    && status.getState().getWaiting().getReason() != null
                    && TERMINAL_WAITING_REASONS.contains(status.getState().getWaiting().getReason())) {
                var waiting = status.getState().getWaiting();
                return status.getName() + ": " + waiting.getReason() + " (" + waiting.getMessage() + ")";
            }
            if (status.getState() != null && status.getState().getTerminated() != null
                    && status.getState().getTerminated().getExitCode() != null
                    && status.getState().getTerminated().getExitCode() != 0) {
                return status.getName() + ": exited with code " + status.getState().getTerminated().getExitCode();
            }
        }
        return null;
    }

}
