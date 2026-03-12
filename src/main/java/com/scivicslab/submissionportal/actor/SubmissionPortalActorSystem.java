package com.scivicslab.submissionportal.actor;

import com.scivicslab.submissionportal.k8s.K8sApiClient;
import com.scivicslab.submissionportal.plugin.ToolPlugin;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.pojoactor.core.ActorSystem;
import com.scivicslab.pojoactor.core.scheduler.Scheduler;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.fabric8.kubernetes.api.model.Pod;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * CDI singleton that initializes and owns the POJO-actor ActorSystem.
 * Creates SessionManagerActor and schedules idle timeout checks.
 */
@ApplicationScoped
@Startup
public class SubmissionPortalActorSystem {

    private static final Logger LOG = Logger.getLogger(SubmissionPortalActorSystem.class.getName());

    @ConfigProperty(name = "subportal.user-pods-namespace", defaultValue = "user-pods")
    String userPodsNamespace;

    @ConfigProperty(name = "subportal.httproute-namespace", defaultValue = "sc-account-bg")
    String httpRouteNamespace;

    @ConfigProperty(name = "subportal.gateway-names", defaultValue = "sc-account-set1-gateway,sc-account-set2-gateway")
    List<String> gatewayNames;

    @ConfigProperty(name = "subportal.idle-timeout-minutes", defaultValue = "1440")
    long idleTimeoutMinutes;

    @ConfigProperty(name = "subportal.max-lifetime-minutes", defaultValue = "10080")
    long maxLifetimeMinutes;

    @ConfigProperty(name = "subportal.max-sessions", defaultValue = "100")
    int maxSessions;

    @ConfigProperty(name = "subportal.max-sessions-per-user", defaultValue = "2")
    int maxSessionsPerUser;

    @ConfigProperty(name = "subportal.unlimited-users", defaultValue = "")
    String unlimitedUsersStr;

    @ConfigProperty(name = "subportal.storage-type-options", defaultValue = "s3,nfs-k8s")
    List<String> storageTypeOptions;

    @ConfigProperty(name = "subportal.default-storage-type", defaultValue = "nfs-k8s")
    String defaultStorageType;

    @ConfigProperty(name = "subportal.session-oidc.issuer")
    String sessionOidcIssuer;

    @ConfigProperty(name = "subportal.session-oidc.authorization-endpoint")
    String sessionOidcAuthorizationEndpoint;

    @ConfigProperty(name = "subportal.session-oidc.token-endpoint")
    String sessionOidcTokenEndpoint;

    @ConfigProperty(name = "subportal.session-oidc.client-id")
    String sessionOidcClientId;

    @ConfigProperty(name = "subportal.session-oidc.secret-name")
    String sessionOidcSecretName;

    @ConfigProperty(name = "subportal.session-oidc.jwks-uri")
    String sessionOidcJwksUri;

    // NFS k8s-dedicated storage config
    @ConfigProperty(name = "subportal.nfs-k8s.server", defaultValue = "")
    String nfsK8sServer;

    @ConfigProperty(name = "subportal.nfs-k8s.base-path", defaultValue = "/Public/submission-portal-data")
    String nfsK8sBasePath;

    private ActorSystem actorSystem;
    private ActorRef<SessionManagerActor> sessionManager;
    private Scheduler scheduler;
    private K8sApiClient k8sClient;

    @PostConstruct
    void init() {
        LOG.info("Initializing submission-portal ActorSystem");

        // Discover tool plugins via ServiceLoader
        Map<String, ToolPlugin> plugins = new LinkedHashMap<>();
        for (ToolPlugin plugin : ServiceLoader.load(ToolPlugin.class)) {
            plugins.put(plugin.name(), plugin);
            LOG.info("Registered tool plugin: " + plugin.name() + " (" + plugin.displayName() + ")");
        }

        // Create ActorSystem
        actorSystem = new ActorSystem("submission-portal");

        // Create K8sApiClient
        k8sClient = new K8sApiClient(userPodsNamespace, httpRouteNamespace, gatewayNames,
            sessionOidcIssuer, sessionOidcAuthorizationEndpoint, sessionOidcTokenEndpoint,
            sessionOidcClientId, sessionOidcSecretName, sessionOidcJwksUri,
            nfsK8sServer, nfsK8sBasePath);

        // Parse unlimited users list
        Set<String> unlimitedUsers = new HashSet<>();
        for (String u : unlimitedUsersStr.split(",")) {
            String trimmed = u.trim();
            if (!trimmed.isEmpty()) unlimitedUsers.add(trimmed);
        }

        // Create SessionManagerActor
        SessionManagerActor manager = new SessionManagerActor(
            k8sClient, plugins, maxSessions, maxSessionsPerUser, idleTimeoutMinutes,
            maxLifetimeMinutes, unlimitedUsers);
        sessionManager = actorSystem.actorOf("session-manager", manager);

        // Schedule idle timeout checks
        scheduler = new Scheduler();
        scheduler.scheduleAtFixedRate("idle-check", sessionManager,
            SessionManagerActor::checkIdleSessions, 60, 60, TimeUnit.SECONDS);

        // Clean up any orphaned resources left from a previous controller instance
        reconcileOrphanedResources();

        LOG.info("submission-portal ActorSystem initialized with " + plugins.size() + " tool plugin(s)");
    }

    @PreDestroy
    void shutdown() {
        LOG.info("Shutting down submission-portal ActorSystem");
        if (scheduler != null) {
            scheduler.close();
        }
        // Do NOT destroy user sessions (Pods, Services, HTTPRoutes) on shutdown.
        // The new controller instance will adopt them via reconcileOrphanedResources().
        // Destroying them here causes a race condition: the old controller deletes
        // HTTPRoutes that the new controller has already re-created during reconciliation.
        if (actorSystem != null) {
            actorSystem.terminate();
        }
    }

    private void reconcileOrphanedResources() {
        LOG.info("Starting orphan resource reconciliation...");
        try {
            // Fetch all managed Pods (with full Pod objects for label extraction and status check)
            List<Pod> managedPods = k8sClient.listManagedPods();

            // Collect orphaned session IDs from Services and HTTPRoutes (no Pod counterpart)
            CompletableFuture<List<String>> servicesFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedServiceSessionIds);
            CompletableFuture<List<String>> routesFuture = CompletableFuture.supplyAsync(
                k8sClient::listManagedHTTPRouteSessionIds);

            // Build set of session IDs that have a Pod
            Set<String> podSessionIds = new HashSet<>();
            for (Pod pod : managedPods) {
                String sid = pod.getMetadata().getLabels().get("session");
                if (sid != null) podSessionIds.add(sid);
            }

            // Restore Running Pods, delete non-Running Pods
            int restored = 0;
            int deleted = 0;
            for (Pod pod : managedPods) {
                Map<String, String> labels = pod.getMetadata().getLabels();
                String sessionId = labels.get("session");
                String toolName = labels.get("tool");
                String userId = labels.get("user");
                if (sessionId == null || toolName == null || userId == null) {
                    LOG.warning("Pod missing required labels, deleting: " + pod.getMetadata().getName());
                    try { k8sClient.deleteOrphanedPodBySession(sessionId != null ? sessionId : "unknown"); }
                    catch (Exception e) { LOG.warning("Failed to delete unlabeled pod: " + e.getMessage()); }
                    continue;
                }

                boolean running = pod.getStatus() != null
                    && "Running".equals(pod.getStatus().getPhase());
                if (running) {
                    // Restore this session
                    sessionManager.tell(sm -> sm.restoreSession(sessionManager, sessionId, userId, toolName, pod));
                    restored++;
                } else {
                    // Non-running Pod -- delete all associated resources
                    LOG.info("Deleting non-running orphaned session: " + sessionId
                        + " (phase=" + (pod.getStatus() != null ? pod.getStatus().getPhase() : "null") + ")");
                    try { k8sClient.deleteHTTPRoute(sessionId); } catch (Exception e) {
                        LOG.warning("Failed to delete orphaned HTTPRoute for " + sessionId + ": " + e.getMessage());
                    }
                    try { k8sClient.deleteService("subportal-svc-" + sessionId); } catch (Exception e) {
                        LOG.warning("Failed to delete orphaned Service for " + sessionId + ": " + e.getMessage());
                    }
                    try { k8sClient.deleteOrphanedPodBySession(sessionId); } catch (Exception e) {
                        LOG.warning("Failed to delete orphaned Pod for " + sessionId + ": " + e.getMessage());
                    }
                    deleted++;
                }
            }

            // Clean up orphaned Services/HTTPRoutes that have no corresponding Pod
            Set<String> orphanedNonPodSessions = new HashSet<>();
            orphanedNonPodSessions.addAll(servicesFuture.get(10, TimeUnit.SECONDS));
            orphanedNonPodSessions.addAll(routesFuture.get(10, TimeUnit.SECONDS));
            orphanedNonPodSessions.removeAll(podSessionIds);
            for (String sessionId : orphanedNonPodSessions) {
                LOG.info("Deleting orphaned non-pod resources for session: " + sessionId);
                try { k8sClient.deleteHTTPRoute(sessionId); } catch (Exception e) {
                    LOG.warning("Failed to delete orphaned HTTPRoute for " + sessionId + ": " + e.getMessage());
                }
                try { k8sClient.deleteService("subportal-svc-" + sessionId); } catch (Exception e) {
                    LOG.warning("Failed to delete orphaned Service for " + sessionId + ": " + e.getMessage());
                }
                deleted++;
            }

            LOG.info("Orphan reconciliation complete: restored=" + restored + ", deleted=" + deleted);
        } catch (Exception e) {
            LOG.warning("Orphan reconciliation failed: " + e.getMessage());
        }
    }

    public ActorRef<SessionManagerActor> getSessionManager() {
        return sessionManager;
    }

    public List<String> getStorageTypeOptions() {
        return storageTypeOptions;
    }

    public String getDefaultStorageType() {
        return defaultStorageType;
    }

    public K8sApiClient getK8sClient() {
        return k8sClient;
    }
}
