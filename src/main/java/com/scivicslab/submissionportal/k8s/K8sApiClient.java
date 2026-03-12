package com.scivicslab.submissionportal.k8s;

import com.scivicslab.submissionportal.plugin.ResourceProfile;
import com.scivicslab.submissionportal.plugin.ToolPlugin;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.gatewayapi.v1.*;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

import jakarta.enterprise.inject.spi.CDI;

import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetricsList;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.Objects;

/**
 * Wraps fabric8 KubernetesClient to provide Pod/Service/HTTPRoute operations
 * for user session Pods.
 *
 * <p>Routing uses Gateway API HTTPRoute (Envoy Gateway) instead of Ingress.
 * Each session creates one HTTPRoute in the gateway namespace with parentRefs
 * to all configured Gateways and a cross-namespace backendRef to the session
 * Service in user-pods namespace.</p>
 */
public class K8sApiClient {

    private static final Logger LOG = Logger.getLogger(K8sApiClient.class.getName());

    private final KubernetesClient client;
    private final String userPodsNamespace;
    private final String httpRouteNamespace;
    private final List<String> gatewayNames;

    // OIDC config for Envoy Gateway SecurityPolicy
    private final String oidcIssuer;
    private final String oidcAuthorizationEndpoint;
    private final String oidcTokenEndpoint;
    private final String oidcClientId;
    private final String oidcSecretName;
    private final String oidcJwksUri;

    // NFS k8s-dedicated config
    private final String nfsK8sServer;
    private final String nfsK8sBasePath;

    public K8sApiClient(String userPodsNamespace, String httpRouteNamespace, List<String> gatewayNames,
                        String oidcIssuer, String oidcAuthorizationEndpoint, String oidcTokenEndpoint,
                        String oidcClientId, String oidcSecretName, String oidcJwksUri,
                        String nfsK8sServer, String nfsK8sBasePath) {
        this.client = CDI.current().select(KubernetesClient.class).get();
        this.userPodsNamespace = userPodsNamespace;
        this.httpRouteNamespace = httpRouteNamespace;
        this.gatewayNames = gatewayNames;
        this.oidcIssuer = oidcIssuer;
        this.oidcAuthorizationEndpoint = oidcAuthorizationEndpoint;
        this.oidcTokenEndpoint = oidcTokenEndpoint;
        this.oidcClientId = oidcClientId;
        this.oidcSecretName = oidcSecretName;
        this.oidcJwksUri = oidcJwksUri;
        this.nfsK8sServer = nfsK8sServer;
        this.nfsK8sBasePath = nfsK8sBasePath;
    }

    // -- Pod operations --

    public CompletableFuture<Pod> createPod(SessionInfo info) {
        return CompletableFuture.supplyAsync(() -> {
            Pod pod = buildPodSpec(info);
            Pod created = client.pods().inNamespace(userPodsNamespace).resource(pod).create();
            LOG.info("Pod created: " + created.getMetadata().getName());
            return created;
        });
    }

    public CompletableFuture<Void> deletePod(String podName) {
        return CompletableFuture.runAsync(() -> {
            client.pods().inNamespace(userPodsNamespace).withName(podName).delete();
            LOG.info("Pod deleted: " + podName);
        });
    }

    /** Delete all pods with the given session label (used by orphan cleanup). */
    public void deleteOrphanedPodBySession(String sessionId) {
        client.pods().inNamespace(userPodsNamespace)
            .withLabel("session", sessionId)
            .delete();
        LOG.info("Orphaned pod(s) deleted for session: " + sessionId);
    }

    /** Set an annotation on a managed Pod. */
    public void setPodAnnotation(String podName, String key, String value) {
        client.pods().inNamespace(userPodsNamespace).withName(podName)
            .edit(p -> new PodBuilder(p)
                .editMetadata().addToAnnotations(key, value).endMetadata()
                .build());
    }

    public String getPodPhase(String podName) {
        Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
        if (pod == null || pod.getStatus() == null) {
            return "Unknown";
        }
        return pod.getStatus().getPhase();
    }

    public boolean isPodReady(String podName) {
        Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
        if (pod == null || pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        return pod.getStatus().getConditions().stream()
            .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Watch a specific Pod for status changes.
     * callback receives (action, pod) on each event.
     * Returns the Watch handle for later closing.
     */
    public Watch watchPod(String podName, BiConsumer<Watcher.Action, Pod> callback) {
        return client.pods().inNamespace(userPodsNamespace).withName(podName).watch(new Watcher<>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                callback.accept(action, pod);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    LOG.warning("Pod watch closed with error: " + cause.getMessage());
                }
            }
        });
    }

    // -- Service operations --

    public Service createService(SessionInfo info) {
        Service svc = new ServiceBuilder()
            .withNewMetadata()
                .withName(info.serviceName())
                .withNamespace(userPodsNamespace)
                .withLabels(Map.of(
                    "app", "submission-portal-user",
                    "session", info.sessionId(),
                    "user", info.userId()
                ))
            .endMetadata()
            .withNewSpec()
                .withSelector(Map.of("session", info.sessionId()))
                .addNewPort()
                    .withPort(info.toolPlugin().containerPort())
                    .withTargetPort(new IntOrString(info.toolPlugin().containerPort()))
                    .withProtocol("TCP")
                .endPort()
            .endSpec()
            .build();

        Service created = client.services().inNamespace(userPodsNamespace).resource(svc).create();
        LOG.info("Service created: " + created.getMetadata().getName());
        return created;
    }

    /**
     * Ensure Service exists for the session. Creates if missing, skips if already present.
     */
    public void ensureService(SessionInfo info) {
        Service existing = client.services().inNamespace(userPodsNamespace)
            .withName(info.serviceName()).get();
        if (existing != null) {
            return;
        }
        createService(info);
        LOG.info("Service re-created during restore: " + info.serviceName());
    }

    public void deleteService(String serviceName) {
        client.services().inNamespace(userPodsNamespace).withName(serviceName).delete();
        LOG.info("Service deleted: " + serviceName);
    }

    // -- HTTPRoute operations (Gateway API) --

    /**
     * Create an HTTPRoute for the session in the gateway namespace.
     * Routes /session/{sessionId}/* to the session Service via URL rewrite.
     * One HTTPRoute with parentRefs to all configured Gateways.
     */
    public void createHTTPRoute(String sessionId, String serviceName, int port, boolean passthroughPath) {
        List<ParentReference> parentRefs = gatewayNames.stream()
            .map(gw -> new ParentReferenceBuilder()
                .withGroup("gateway.networking.k8s.io")
                .withKind("Gateway")
                .withName(gw)
                .build())
            .toList();

        HTTPRoute route = new HTTPRouteBuilder()
            .withNewMetadata()
                .withName("subportal-session-" + sessionId)
                .withNamespace(httpRouteNamespace)
                .withLabels(Map.of(
                    "managed-by", "submission-portal",
                    "session", sessionId
                ))
            .endMetadata()
            .withNewSpec()
                .withParentRefs(parentRefs)
                .addNewRule()
                    .addNewMatch()
                        .withNewPath()
                            .withType("PathPrefix")
                            .withValue("/sp-session/" + sessionId)
                        .endPath()
                    .endMatch()
                    // Rewrite /session/{id}/* -> /* unless passthroughPath is set.
                    // Tools that need to know their base URL (e.g. JupyterLab) use passthrough
                    // and configure themselves via the PUPS_SESSION_PATH env var instead.
                    .addAllToFilters(passthroughPath ? List.of() : List.of(
                        new HTTPRouteFilterBuilder()
                            .withType("URLRewrite")
                            .withNewUrlRewrite()
                                .withNewPath()
                                    .withType("ReplacePrefixMatch")
                                    .withReplacePrefixMatch("/")
                                .endPath()
                            .endUrlRewrite()
                            .build()
                    ))
                    .addNewFilter()
                        .withType("RequestHeaderModifier")
                        .withNewRequestHeaderModifier()
                            .addNewSet()
                                .withName("X-Forwarded-Proto")
                                .withValue("https")
                            .endSet()
                        .endRequestHeaderModifier()
                    .endFilter()
                    .addNewBackendRef()
                        .withName(serviceName)
                        .withNamespace(userPodsNamespace)
                        .withPort(port)
                    .endBackendRef()
                    .withNewTimeouts()
                        .withRequest("3600s")
                    .endTimeouts()
                .endRule()
            .endSpec()
            .build();

        client.resource(route).create();
        LOG.info("HTTPRoute created: subportal-session-" + sessionId
            + " (gateways: " + String.join(", ", gatewayNames) + ")");
    }

    /**
     * Ensure HTTPRoute exists for the session. Creates if missing, skips if already present.
     */
    public void ensureHTTPRoute(String sessionId, String serviceName, int port, boolean passthroughPath) {
        String routeName = "subportal-session-" + sessionId;
        HTTPRoute existing = client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withName(routeName)
            .get();
        if (existing != null) {
            return;
        }
        createHTTPRoute(sessionId, serviceName, port, passthroughPath);
        LOG.info("HTTPRoute re-created during restore: " + routeName);
    }

    /**
     * Delete the HTTPRoute for the session.
     */
    public void deleteHTTPRoute(String sessionId) {
        String routeName = "subportal-session-" + sessionId;
        client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withName(routeName)
            .delete();
        LOG.info("HTTPRoute deleted: " + routeName);
    }

    // -- SecurityPolicy operations (Envoy Gateway OIDC) --

    private static final ResourceDefinitionContext SECURITY_POLICY_CONTEXT =
        new ResourceDefinitionContext.Builder()
            .withGroup("gateway.envoyproxy.io")
            .withVersion("v1alpha1")
            .withKind("SecurityPolicy")
            .withPlural("securitypolicies")
            .withNamespaced(true)
            .build();

    /**
     * Create an OIDC SecurityPolicy for the session HTTPRoute.
     * Enforces Keycloak OIDC authentication on the session route and restricts
     * access to the session owner only (JWT sub claim must match userId).
     */
    public void createSecurityPolicy(String sessionId, String userId) {
        String policyName = "subportal-session-sp-" + sessionId;
        String targetRouteName = "subportal-session-" + sessionId;
        String redirectURL = "https://192.168.5.25/session/" + sessionId + "/oauth2/callback";
        // Fixed cookie name (per-session to avoid cross-session conflicts).
        String idTokenCookieName = "subportal-id-" + sessionId;

        // Build spec as nested Maps (SecurityPolicy CRD is not in fabric8 built-in model)
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("targetRefs", List.of(Map.of(
            "group", "gateway.networking.k8s.io",
            "kind", "HTTPRoute",
            "name", targetRouteName
        )));

        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("issuer", oidcIssuer);
        provider.put("authorizationEndpoint", oidcAuthorizationEndpoint);
        provider.put("tokenEndpoint", oidcTokenEndpoint);

        Map<String, Object> oidc = new LinkedHashMap<>();
        oidc.put("provider", provider);
        oidc.put("clientID", oidcClientId);
        oidc.put("clientSecret", Map.of("name", oidcSecretName));
        oidc.put("redirectURL", redirectURL);
        oidc.put("scopes", List.of("openid", "profile"));
        oidc.put("cookieNames", Map.of("idToken", idTokenCookieName));

        spec.put("oidc", oidc);

        // JWT section: extract the OIDC ID token from the fixed-name cookie and validate it.
        String jwksUri = oidcJwksUri;
        Map<String, Object> remoteJWKS = new LinkedHashMap<>();
        remoteJWKS.put("uri", jwksUri);

        Map<String, Object> extractFrom = new LinkedHashMap<>();
        extractFrom.put("cookies", List.of(idTokenCookieName));

        Map<String, Object> jwtProvider = new LinkedHashMap<>();
        jwtProvider.put("name", "keycloak");
        jwtProvider.put("issuer", oidcIssuer);
        jwtProvider.put("remoteJWKS", remoteJWKS);
        jwtProvider.put("extractFrom", extractFrom);

        spec.put("jwt", Map.of("providers", List.of(jwtProvider)));

        // Authorization section: allow only the session owner (preferred_username == userId).
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("name", "preferred_username");
        claim.put("values", List.of(userId));

        Map<String, Object> jwtPrincipal = new LinkedHashMap<>();
        jwtPrincipal.put("provider", "keycloak");
        jwtPrincipal.put("claims", List.of(claim));

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", "owner-only");
        rule.put("action", "Allow");
        rule.put("principal", Map.of("jwt", jwtPrincipal));

        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("defaultAction", "Deny");
        authorization.put("rules", List.of(rule));

        spec.put("authorization", authorization);

        GenericKubernetesResource sp = new GenericKubernetesResource();
        sp.setApiVersion("gateway.envoyproxy.io/v1alpha1");
        sp.setKind("SecurityPolicy");
        sp.setMetadata(new ObjectMetaBuilder()
            .withName(policyName)
            .withNamespace(httpRouteNamespace)
            .withLabels(Map.of(
                "managed-by", "submission-portal",
                "session", sessionId
            ))
            .build());
        sp.setAdditionalProperty("spec", spec);

        client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .resource(sp)
            .create();

        LOG.info("SecurityPolicy created: " + policyName + " for HTTPRoute " + targetRouteName);
    }

    /**
     * Delete the OIDC SecurityPolicy for the session.
     */
    public void deleteSecurityPolicy(String sessionId) {
        String policyName = "subportal-session-sp-" + sessionId;
        client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .withName(policyName)
            .delete();
        LOG.info("SecurityPolicy deleted: " + policyName);
    }

    // -- Orphan resource discovery (for startup reconciliation) --

    private static final String MANAGED_BY_LABEL = "managed-by=submission-portal";

    /**
     * Returns all Pods in userPodsNamespace created by submission-portal.
     * Used during startup reconciliation to restore or clean up sessions.
     */
    public List<Pod> listManagedPods() {
        return client.pods()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems();
    }

    /**
     * Returns sessionIds of all Pods in userPodsNamespace created by submission-portal.
     * Used during startup reconciliation to identify orphaned resources.
     */
    public List<String> listManagedPodSessionIds() {
        return client.pods()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(pod -> pod.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all HTTPRoutes in httpRouteNamespace created by submission-portal.
     */
    public List<String> listManagedHTTPRouteSessionIds() {
        return client.resources(HTTPRoute.class)
            .inNamespace(httpRouteNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(r -> r.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all Services in userPodsNamespace created by submission-portal.
     */
    public List<String> listManagedServiceSessionIds() {
        return client.services()
            .inNamespace(userPodsNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(s -> s.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Returns sessionIds of all SecurityPolicies in httpRouteNamespace created by submission-portal.
     */
    public List<String> listManagedSecurityPolicySessionIds() {
        return client.genericKubernetesResources(SECURITY_POLICY_CONTEXT)
            .inNamespace(httpRouteNamespace)
            .withLabelSelector(MANAGED_BY_LABEL)
            .list()
            .getItems()
            .stream()
            .map(r -> r.getMetadata().getLabels().get("session"))
            .filter(Objects::nonNull)
            .toList();
    }

    // -- PVC operations --

    /**
     * Returns the sanitized userId for use in k8s resource names.
     */
    private String sanitizeUserId(String userId) {
        return userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    /**
     * Returns the PVC name for a given userId and storage type.
     * Format: subportal-data-{sanitizedUserId}-{storageType}
     */
    public String userPvcName(String userId, String storageType) {
        return "subportal-data-" + sanitizeUserId(userId) + "-" + storageType;
    }

    /**
     * Returns the PV name for NFS-based storage types.
     * PV is cluster-scoped; name matches PVC for easy pairing.
     */
    private String userPvName(String userId, String storageType) {
        return userPvcName(userId, storageType);
    }

    /**
     * Creates a PV + PVC pair for the user's k8s-dedicated NFS storage.
     * First ensures the user directory exists on the NFS server (owned by UID 1000),
     * then creates the PV/PVC pointing to it.
     * Uses nfs.csi.k8s.io driver with the nfsK8sServer/nfsK8sBasePath config.
     * If they already exist, this is a no-op.
     *
     * @param userId the user identifier
     */
    public void createNfsK8sPvPvc(String userId) {
        String pvName = userPvName(userId, "nfs-k8s");
        String pvcName = userPvcName(userId, "nfs-k8s");
        String sanitized = sanitizeUserId(userId);
        String nfsPath = nfsK8sBasePath + "/" + sanitized;

        // Ensure user directory exists on NFS server before creating PV
        PersistentVolume existingPv = client.persistentVolumes().withName(pvName).get();
        if (existingPv == null) {
            ensureNfsK8sDirectory(sanitized);

            PersistentVolume pv = new PersistentVolumeBuilder()
                .withNewMetadata()
                    .withName(pvName)
                    .addToLabels("app", "submission-portal-user")
                    .addToLabels("managed-by", "submission-portal")
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-k8s")
                .endMetadata()
                .withNewSpec()
                    .withCapacity(Map.of("storage", new Quantity("1Ti")))
                    .withAccessModes("ReadWriteMany")
                    .withPersistentVolumeReclaimPolicy("Retain")
                    .withStorageClassName("")
                    .withNewCsi()
                        .withDriver("nfs.csi.k8s.io")
                        .withVolumeHandle(pvName)
                        .addToVolumeAttributes("server", nfsK8sServer)
                        .addToVolumeAttributes("share", nfsPath)
                    .endCsi()
                    .withMountOptions(List.of("nfsvers=4"))
                .endSpec()
                .build();
            client.persistentVolumes().resource(pv).create();
            LOG.info("NFS-k8s PV created: " + pvName + " -> " + nfsK8sServer + ":" + nfsPath);
        }

        // Create PVC if absent
        PersistentVolumeClaim existingPvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (existingPvc == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "submission-portal-user")
                    .addToLabels("managed-by", "submission-portal")
                    .addToLabels("user", userId)
                    .addToLabels("storage-type", "nfs-k8s")
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteMany")
                    .withStorageClassName("")
                    .withVolumeName(pvName)
                    .withNewResources()
                        .addToRequests("storage", new Quantity("1Ti"))
                    .endResources()
                .endSpec()
                .build();
            client.persistentVolumeClaims().inNamespace(userPodsNamespace).resource(pvc).create();
            LOG.info("NFS-k8s PVC created: " + pvcName);
        }
    }

    /**
     * Ensures the user directory exists on the NFS server for nfs-k8s storage.
     * Runs a temporary Pod that mounts the NFS base path and creates the user subdirectory
     * with ownership set to 1000:1000.
     */
    private void ensureNfsK8sDirectory(String sanitizedUserId) {
        String podName = "nfs-k8s-init-" + sanitizedUserId + "-" + System.currentTimeMillis() % 100000;
        String cmd = "mkdir -p /mnt/nfs-base/" + sanitizedUserId
            + " && chown 1000:1000 /mnt/nfs-base/" + sanitizedUserId
            + " && echo 'Directory ready'";

        Pod initPod = new PodBuilder()
            .withNewMetadata()
                .withName(podName)
                .withNamespace(userPodsNamespace)
                .addToLabels("app", "submission-portal-nfs-init")
                .addToLabels("managed-by", "submission-portal")
            .endMetadata()
            .withNewSpec()
                .withRestartPolicy("Never")
                .addNewVolume()
                    .withName("nfs-base")
                    .withNewNfs()
                        .withServer(nfsK8sServer)
                        .withPath(nfsK8sBasePath)
                    .endNfs()
                .endVolume()
                .addNewContainer()
                    .withName("init")
                    .withImage("busybox:1.36")
                    .withCommand("sh", "-c", cmd)
                    .addNewVolumeMount()
                        .withName("nfs-base")
                        .withMountPath("/mnt/nfs-base")
                    .endVolumeMount()
                .endContainer()
            .endSpec()
            .build();

        try {
            client.pods().inNamespace(userPodsNamespace).resource(initPod).create();
            LOG.info("NFS-k8s init pod created: " + podName);

            // Wait for the init pod to complete (up to 60 seconds)
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                Pod pod = client.pods().inNamespace(userPodsNamespace).withName(podName).get();
                if (pod == null) break;
                String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
                if ("Succeeded".equals(phase)) {
                    LOG.info("NFS-k8s directory created for: " + sanitizedUserId);
                    break;
                } else if ("Failed".equals(phase)) {
                    LOG.warning("NFS-k8s init pod failed for: " + sanitizedUserId);
                    break;
                }
            }
        } finally {
            // Clean up init pod
            try {
                client.pods().inNamespace(userPodsNamespace).withName(podName).delete();
            } catch (Exception e) {
                LOG.warning("Failed to delete NFS-k8s init pod " + podName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Deletes a user's PVC (and PV for NFS types).
     * Refuses to delete if the PVC is currently mounted by a Pod.
     *
     * @return true if deleted, false if in-use or not found
     */
    public boolean deleteUserPvc(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null) {
            LOG.info("PVC not found for deletion: " + pvcName);
            return false;
        }
        if (isUserPvcInUse(userId, storageType)) {
            LOG.warning("Cannot delete PVC " + pvcName + ": currently mounted by a Pod");
            return false;
        }

        // Delete PVC
        client.persistentVolumeClaims().inNamespace(userPodsNamespace).withName(pvcName).delete();
        LOG.info("PVC deleted: " + pvcName);

        // For NFS types, also delete the PV
        if ("nfs-k8s".equals(storageType)) {
            String pvName = userPvName(userId, storageType);
            try {
                client.persistentVolumes().withName(pvName).delete();
                LOG.info("PV deleted: " + pvName);
            } catch (Exception e) {
                LOG.warning("Failed to delete PV " + pvName + ": " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * Returns the names of Pods currently mounting a user's PVC.
     */
    public List<String> findPodsUsingPvc(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        List<String> podNames = new ArrayList<>();
        var pods = client.pods().inNamespace(userPodsNamespace)
            .withLabel("managed-by", "submission-portal").list().getItems();
        for (var pod : pods) {
            if (pod.getSpec() == null || pod.getSpec().getVolumes() == null) continue;
            // Only count Running/Pending pods (not terminated)
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "";
            if (!"Running".equals(phase) && !"Pending".equals(phase)) continue;
            for (var vol : pod.getSpec().getVolumes()) {
                if (vol.getPersistentVolumeClaim() != null
                        && pvcName.equals(vol.getPersistentVolumeClaim().getClaimName())) {
                    podNames.add(pod.getMetadata().getName());
                }
            }
        }
        return podNames;
    }

    /**
     * Checks if a user's PVC is currently mounted by any Pod.
     */
    public boolean isUserPvcInUse(String userId, String storageType) {
        return !findPodsUsingPvc(userId, storageType).isEmpty();
    }

    /**
     * Returns PVC info for a specific storage type.
     */
    public Map<String, String> getUserPvcInfo(String userId, String storageType) {
        String pvcName = userPvcName(userId, storageType);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims()
            .inNamespace(userPodsNamespace).withName(pvcName).get();
        if (pvc == null) {
            return Map.of("exists", "false");
        }
        Quantity size = pvc.getSpec().getResources().getRequests().get("storage");
        String phase = pvc.getStatus() != null && pvc.getStatus().getPhase() != null
            ? pvc.getStatus().getPhase() : "Unknown";
        String sc = pvc.getSpec().getStorageClassName() != null
            ? pvc.getSpec().getStorageClassName() : "";
        List<String> usingPods = findPodsUsingPvc(userId, storageType);
        boolean inUse = !usingPods.isEmpty();
        Map<String, String> result = new HashMap<>();
        result.put("exists", "true");
        result.put("size", size != null ? size.toString() : "0");
        result.put("phase", phase);
        result.put("storageClass", sc);
        result.put("inUse", String.valueOf(inUse));
        result.put("usedBy", String.join(",", usingPods));
        return result;
    }

    /**
     * Returns PVC info for all supported storage types (nfs-k8s only).
     */
    public Map<String, Object> getAllUserPvcInfo(String userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("nfs-k8s", getUserPvcInfo(userId, "nfs-k8s"));
        return result;
    }

    /**
     * Reads the user's storage preferences from their ConfigMap.
     * Returns all ConfigMap data including activeStorageType.
     */
    public Map<String, String> getUserStorageInfo(String userId) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap cm = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (cm == null || cm.getData() == null) {
            return Collections.emptyMap();
        }
        return new HashMap<>(cm.getData());
    }

    /**
     * Saves the user's active storage type to the ConfigMap.
     */
    public void setActiveStorageType(String userId, String storageType) {
        updateUserPref(userId, "activeStorageType", storageType);
        // Legacy compat
        updateUserPref(userId, "storageType", storageType);
        LOG.info("Active storage type set for " + userId + ": " + storageType);
    }

    /**
     * Saves the user's storage preferences to a ConfigMap.
     * Creates the ConfigMap if it does not exist.
     */
    public void saveUserStoragePreference(String userId, String storageSize, String storageType) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap existing = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (existing != null) {
            if (existing.getData() == null) {
                existing.setData(new HashMap<>());
            }
            existing.getData().put("storageSize", storageSize);
            existing.getData().put("storageType", storageType);
            existing.getData().put("activeStorageType", storageType);
            client.configMaps().inNamespace(userPodsNamespace)
                .resource(existing).update();
        } else {
            ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "submission-portal-user")
                    .addToLabels("managed-by", "submission-portal")
                    .addToLabels("user", userId)
                .endMetadata()
                .addToData("storageSize", storageSize)
                .addToData("storageType", storageType)
                .addToData("activeStorageType", storageType)
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
        LOG.info("Saved storage preference for " + userId + ": " + storageSize + " (" + storageType + ")");
    }

    /**
     * Updates a single key in the user's preferences ConfigMap.
     * Creates the ConfigMap if it does not exist.
     */
    private void updateUserPref(String userId, String key, String value) {
        String cmName = userPrefsConfigMapName(userId);
        ConfigMap existing = client.configMaps()
            .inNamespace(userPodsNamespace)
            .withName(cmName)
            .get();
        if (existing != null) {
            if (existing.getData() == null) {
                existing.setData(new HashMap<>());
            }
            existing.getData().put(key, value);
            client.configMaps().inNamespace(userPodsNamespace)
                .resource(existing).update();
        } else {
            ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                    .withName(cmName)
                    .withNamespace(userPodsNamespace)
                    .addToLabels("app", "submission-portal-user")
                    .addToLabels("managed-by", "submission-portal")
                    .addToLabels("user", userId)
                .endMetadata()
                .addToData(key, value)
                .build();
            client.configMaps().inNamespace(userPodsNamespace).resource(cm).create();
        }
    }

    private String userPrefsConfigMapName(String userId) {
        return "subportal-prefs-" + userId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    // -- Internal --

    private ResourceProfile resolveProfile(ToolPlugin plugin, String profileName) {
        List<ResourceProfile> profiles = plugin.resourceProfiles();
        if (profileName != null) {
            for (ResourceProfile p : profiles) {
                if (p.name().equals(profileName)) {
                    return p;
                }
            }
        }
        return profiles.get(0);
    }

    private Probe buildReadinessProbe(ToolPlugin plugin) {
        if (plugin.readinessProbePath() == null) {
            return null;
        }
        return new ProbeBuilder()
            .withNewHttpGet()
                .withPath(plugin.readinessProbePath())
                .withPort(new IntOrString(plugin.containerPort()))
            .endHttpGet()
            .withInitialDelaySeconds(plugin.readinessProbeInitialDelay())
            .withPeriodSeconds(plugin.readinessProbePeriod())
            .withFailureThreshold(15)
            .build();
    }

    private Pod buildPodSpec(SessionInfo info) {
        ToolPlugin plugin = info.toolPlugin();

        // Build env vars: plugin-defined + PUPS_SESSION_PATH always injected.
        List<EnvVar> envVars = new ArrayList<>(plugin.environmentVariables().entrySet().stream()
            .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
            .toList());
        envVars.add(new EnvVarBuilder()
            .withName("PUPS_SESSION_PATH")
            .withValue("/sp-session/" + info.sessionId() + "/")
            .build());

        // Inject user-provided parameters as env vars (e.g. API keys)
        if (info.userParams() != null) {
            for (var entry : info.userParams().entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    envVars.add(new EnvVarBuilder()
                        .withName(entry.getKey())
                        .withValue(entry.getValue())
                        .build());
                }
            }
        }

        // Build resource requirements from selected profile
        ResourceProfile profile = resolveProfile(plugin, info.resourceProfile());
        Map<String, Quantity> profileRequests = new HashMap<>();
        profile.requests().forEach((k, v) -> profileRequests.put(k, new Quantity(v)));
        Map<String, Quantity> profileLimits = new HashMap<>();
        profile.limits().forEach((k, v) -> profileLimits.put(k, new Quantity(v)));
        LOG.info("Pod " + info.podName() + " using resource profile: " + profile.name()
            + " (" + profile.displayName() + ")");

        // Storage type is always nfs-k8s
        String storageType = info.userStorageType() != null && !info.userStorageType().isBlank()
            ? info.userStorageType() : "nfs-k8s";

        Map<String, String> labels = Map.of(
            "app", "submission-portal-user",
            "managed-by", "submission-portal",
            "tool", plugin.name(),
            "session", info.sessionId(),
            "user", info.userId(),
            "storage-type", storageType
        );

        return buildSingleContainerPod(info, plugin, envVars, profileRequests, profileLimits,
            storageType, labels);
    }

    /**
     * Builds a single-container Pod.
     * Storage is always nfs-k8s PVC mounted at userDataMountPath.
     */
    private Pod buildSingleContainerPod(SessionInfo info, ToolPlugin plugin,
            List<EnvVar> envVars, Map<String, Quantity> requests, Map<String, Quantity> limits,
            String storageType, Map<String, String> labels) {

        // Volume mounts: /tmp always + plugin-specific writable paths + storage volume
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            mounts.add(new VolumeMountBuilder()
                .withName("writable-" + i)
                .withMountPath(plugin.writablePaths().get(i))
                .build());
        }
        if (plugin.userDataMountPath() != null) {
            mounts.add(new VolumeMountBuilder()
                .withName("user-data")
                .withMountPath(plugin.userDataMountPath())
                .build());
        }

        List<Volume> volumes = new ArrayList<>();
        volumes.add(new VolumeBuilder().withName("tmp")
            .withNewEmptyDir().withSizeLimit(new Quantity("1Gi")).endEmptyDir().build());
        for (int i = 0; i < plugin.writablePaths().size(); i++) {
            volumes.add(new VolumeBuilder().withName("writable-" + i)
                .withNewEmptyDir().withSizeLimit(new Quantity("500Mi")).endEmptyDir().build());
        }
        if (plugin.userDataMountPath() != null) {
            volumes.add(new VolumeBuilder().withName("user-data")
                .withNewPersistentVolumeClaim()
                    .withClaimName(userPvcName(info.userId(), storageType))
                    .withReadOnly(false)
                .endPersistentVolumeClaim()
                .build());
        }

        // Additional mounts (secondary PVCs at user-specified paths)
        if (info.additionalMounts() != null) {
            int idx = 0;
            for (MountSpec extra : info.additionalMounts()) {
                String volName = "extra-data-" + idx;
                String pvcName = userPvcName(info.userId(), extra.storageType());

                mounts.add(new VolumeMountBuilder()
                    .withName(volName)
                    .withMountPath(extra.mountPath())
                    .build());

                volumes.add(new VolumeBuilder().withName(volName)
                    .withNewPersistentVolumeClaim()
                        .withClaimName(pvcName)
                        .withReadOnly(false)
                    .endPersistentVolumeClaim()
                    .build());
                idx++;
            }
        }

        Long runAsUid = plugin.runAsUser();
        Long runAsGid = plugin.runAsUser();
        boolean readOnlyRoot = plugin.readOnlyRootFilesystem();

        // Init container: seed PVC with /etc/skel if empty.
        List<io.fabric8.kubernetes.api.model.Container> initContainers = new ArrayList<>();
        if (plugin.userDataMountPath() != null) {
            String seedCmd =
                "if [ -z \"$(ls -A /mnt/pvc 2>/dev/null | sed '/^lost+found$/d')\" ]; then "
                + "echo 'PVC is empty, seeding from /etc/skel'; "
                + "cp -a /etc/skel/. /mnt/pvc/ 2>/dev/null || true; "
                + "echo 'Seed complete'; "
                + "else echo 'PVC already has data, skipping seed'; fi";
            initContainers.add(new io.fabric8.kubernetes.api.model.ContainerBuilder()
                .withName("seed-home")
                .withImage(plugin.containerImage())
                .withCommand("sh", "-c", seedCmd)
                .withVolumeMounts(new VolumeMountBuilder()
                    .withName("user-data")
                    .withMountPath("/mnt/pvc")
                    .build())
                .withNewSecurityContext()
                    .withAllowPrivilegeEscalation(false)
                    .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .endSecurityContext()
                .withNewResources()
                    .addToRequests("cpu", new Quantity("50m"))
                    .addToRequests("memory", new Quantity("64Mi"))
                    .addToLimits("cpu", new Quantity("200m"))
                    .addToLimits("memory", new Quantity("128Mi"))
                .endResources()
                .build());
        }

        var podBuilder = new PodBuilder()
            .withNewMetadata()
                .withName(info.podName())
                .withNamespace(userPodsNamespace)
                .withLabels(labels)
            .endMetadata()
            .withNewSpec()
                .withAutomountServiceAccountToken(false)
                .withNewSecurityContext()
                    .withRunAsNonRoot(plugin.runAsNonRoot())
                    .withRunAsUser(runAsUid)
                    .withRunAsGroup(runAsGid)
                    .withFsGroup(runAsGid)
                    .withNewSeccompProfile().withType(plugin.seccompProfileType()).endSeccompProfile()
                .endSecurityContext()
                .addNewContainer()
                    .withName("tool")
                    .withImage(plugin.containerImage())
                    .addNewPort()
                        .withContainerPort(plugin.containerPort())
                        .withProtocol("TCP")
                    .endPort()
                    .withEnv(envVars)
                    .withNewResources()
                        .withRequests(requests)
                        .withLimits(limits)
                    .endResources()
                    .withVolumeMounts(mounts)
                    .withNewSecurityContext()
                        .withAllowPrivilegeEscalation(false)
                        .withReadOnlyRootFilesystem(readOnlyRoot)
                        .withNewCapabilities().addToDrop("ALL").endCapabilities()
                    .endSecurityContext()
                    .withReadinessProbe(buildReadinessProbe(plugin))
                .endContainer()
                .withVolumes(volumes)
                .withRestartPolicy("Never")
            .endSpec();

        if (!initContainers.isEmpty()) {
            podBuilder.editSpec().withInitContainers(initContainers).endSpec();
        }

        return podBuilder.build();
    }

    // -- Cluster resource summary --

    /**
     * Returns per-node cluster resource usage by combining Node capacity/allocatable
     * with real-time metrics from metrics-server.
     */
    public Map<String, Object> getClusterResourceSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();

        var nodes = client.nodes().list().getItems();
        int nodeCount = nodes.size();
        long totalCpuMillis = 0;
        long totalMemBytes = 0;

        for (var node : nodes) {
            var allocatable = node.getStatus().getAllocatable();
            totalCpuMillis += parseCpuToMillis(allocatable.get("cpu").toString());
            totalMemBytes += parseMemoryToBytes(allocatable.get("memory").toString());
        }

        // Actual usage from metrics-server
        long usedCpuMillis = 0;
        long usedMemBytes = 0;
        boolean metricsAvailable = false;
        try {
            NodeMetricsList metricsList = client.top().nodes().metrics();
            for (NodeMetrics nm : metricsList.getItems()) {
                usedCpuMillis += parseCpuToMillis(nm.getUsage().get("cpu").toString());
                usedMemBytes += parseMemoryToBytes(nm.getUsage().get("memory").toString());
            }
            metricsAvailable = true;
        } catch (Exception e) {
            LOG.warning("Failed to fetch node metrics: " + e.getMessage());
        }

        // Sum of requests and limits from all running pods
        long requestsCpuMillis = 0;
        long requestsMemBytes = 0;
        long limitsCpuMillis = 0;
        long limitsMemBytes = 0;
        var pods = client.pods().inAnyNamespace().list().getItems();
        for (var pod : pods) {
            if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                continue;
            }
            for (var container : pod.getSpec().getContainers()) {
                var resources = container.getResources();
                if (resources == null) continue;
                var req = resources.getRequests();
                if (req != null) {
                    if (req.get("cpu") != null)
                        requestsCpuMillis += parseCpuToMillis(req.get("cpu").toString());
                    if (req.get("memory") != null)
                        requestsMemBytes += parseMemoryToBytes(req.get("memory").toString());
                }
                var lim = resources.getLimits();
                if (lim != null) {
                    if (lim.get("cpu") != null)
                        limitsCpuMillis += parseCpuToMillis(lim.get("cpu").toString());
                    if (lim.get("memory") != null)
                        limitsMemBytes += parseMemoryToBytes(lim.get("memory").toString());
                }
            }
        }

        summary.put("nodeCount", nodeCount);
        summary.put("cpuCores", totalCpuMillis / 1000);
        summary.put("memoryGi", totalMemBytes / (1024L * 1024 * 1024));
        // Actual usage
        summary.put("cpuUsedCores", usedCpuMillis / 1000);
        summary.put("memoryUsedGi", usedMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuPercent", totalCpuMillis > 0 ? (int) (usedCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryPercent", totalMemBytes > 0 ? (int) (usedMemBytes * 100 / totalMemBytes) : 0);
        // Requests (guaranteed)
        summary.put("cpuReqCores", requestsCpuMillis / 1000);
        summary.put("memoryReqGi", requestsMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuReqPercent", totalCpuMillis > 0 ? (int) (requestsCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryReqPercent", totalMemBytes > 0 ? (int) (requestsMemBytes * 100 / totalMemBytes) : 0);
        // Limits (burst max)
        summary.put("cpuLimCores", limitsCpuMillis / 1000);
        summary.put("memoryLimGi", limitsMemBytes / (1024L * 1024 * 1024));
        summary.put("cpuLimPercent", totalCpuMillis > 0 ? (int) (limitsCpuMillis * 100 / totalCpuMillis) : 0);
        summary.put("memoryLimPercent", totalMemBytes > 0 ? (int) (limitsMemBytes * 100 / totalMemBytes) : 0);
        summary.put("metricsAvailable", metricsAvailable);

        // PVC storage summary
        long pvcTotalBytes = 0;
        int pvcCount = 0;
        var pvcList = client.persistentVolumeClaims().inAnyNamespace().list().getItems();
        for (var pvc : pvcList) {
            var req = pvc.getSpec().getResources().getRequests();
            if (req != null && req.get("storage") != null) {
                pvcTotalBytes += parseMemoryToBytes(req.get("storage").toString());
                pvcCount++;
            }
        }
        summary.put("pvcCount", pvcCount);
        summary.put("pvcTotalGi", pvcTotalBytes / (1024L * 1024 * 1024));

        // ResourceQuota summary across all namespaces
        long quotaStorageBytes = 0;
        boolean hasStorageQuota = false;
        var quotaList = client.resourceQuotas().inAnyNamespace().list().getItems();
        for (var quota : quotaList) {
            var hard = quota.getSpec().getHard();
            if (hard != null && hard.get("requests.storage") != null) {
                quotaStorageBytes += parseMemoryToBytes(hard.get("requests.storage").toString());
                hasStorageQuota = true;
            }
        }
        summary.put("hasStorageQuota", hasStorageQuota);
        summary.put("storageQuotaGi", quotaStorageBytes / (1024L * 1024 * 1024));

        return summary;
    }

    /** Parses CPU quantity string (e.g. "64", "3623m", "500n") to millicores. */
    private long parseCpuToMillis(String cpu) {
        if (cpu.endsWith("n")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1)) / 1_000_000;
        } else if (cpu.endsWith("u")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1)) / 1_000;
        } else if (cpu.endsWith("m")) {
            return Long.parseLong(cpu.substring(0, cpu.length() - 1));
        } else {
            return Long.parseLong(cpu) * 1000;
        }
    }

    /** Parses memory quantity string (e.g. "131728508Ki", "19274Mi", "1024") to bytes. */
    private long parseMemoryToBytes(String mem) {
        if (mem.endsWith("Ki")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024;
        } else if (mem.endsWith("Mi")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024 * 1024;
        } else if (mem.endsWith("Gi")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024L * 1024 * 1024;
        } else if (mem.endsWith("Ti")) {
            return Long.parseLong(mem.substring(0, mem.length() - 2)) * 1024L * 1024 * 1024 * 1024;
        } else {
            return Long.parseLong(mem);
        }
    }
}
