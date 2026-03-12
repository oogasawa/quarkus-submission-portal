package com.scivicslab.submissionportal.resource;

import com.scivicslab.submissionportal.actor.SessionManagerActor;
import com.scivicslab.submissionportal.actor.SessionState;
import com.scivicslab.submissionportal.actor.SessionStatus;
import com.scivicslab.submissionportal.actor.SessionSummary;
import com.scivicslab.submissionportal.actor.SubmissionPortalActorSystem;
import com.scivicslab.submissionportal.k8s.MountSpec;
import com.scivicslab.submissionportal.model.InsdcRegistration;
import com.scivicslab.submissionportal.plugin.ToolPlugin;
import com.scivicslab.submissionportal.plugin.UserParameter;
import com.scivicslab.submissionportal.s3.S3StorageClient;
import com.scivicslab.submissionportal.storage.NfsStorageClient;
import com.scivicslab.submissionportal.service.InsdcRegistrationService;
import com.scivicslab.pojoactor.core.ActorRef;

import io.quarkus.oidc.IdToken;
import io.quarkus.qute.Template;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.jwt.JsonWebToken;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

@Path("/")
public class DashboardResource {

    private static final Logger LOG = Logger.getLogger(DashboardResource.class.getName());

    @Inject
    SubmissionPortalActorSystem actorSystem;

    @Inject
    InsdcRegistrationService registrationService;

    @Inject
    S3StorageClient s3StorageClient;

    @Inject
    NfsStorageClient nfsStorageClient;

    @Inject
    @IdToken
    JsonWebToken idToken;

    @Inject
    SecurityIdentity identity;

    @Inject
    Template dashboard;

    @io.quarkus.qute.Location("storage-files")
    @Inject
    Template storageFiles;

    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/submission-portal")
    String basePath;

    @ConfigProperty(name = "subportal.session-oidc.authorization-endpoint")
    String oidcAuthorizationEndpoint;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    // ── Japanese routes (default) ──

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String index() {
        return renderLanding("ja");
    }

    // ── English routes (/en/) ──

    @GET
    @Path("/en/")
    @Produces(MediaType.TEXT_HTML)
    public String indexEn() {
        return renderLanding("en");
    }

    @GET
    @Path("/en/dashboard")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response showDashboardEn(@QueryParam("creating") String creating) {
        return Response.ok(renderDashboard("en", creating))
            .header("Cache-Control", "no-store")
            .build();
    }

    @GET
    @Path("/dashboard")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public Response showDashboard(@QueryParam("creating") String creating) {
        return Response.ok(renderDashboard("ja", creating))
            .header("Cache-Control", "no-store")
            .build();
    }

    @POST
    @Path("/session/start")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response startSession(@FormParam("tool") String toolName,
                                  @FormParam("profile") String profile,
                                  @FormParam("storageType") String storageType,
                                  @FormParam("userParam_0") String userParam0,
                                  @FormParam("userParam_1") String userParam1,
                                  @FormParam("userParam_2") String userParam2) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Resolve user-provided parameters to env var name -> value map
        Map<String, String> userParams = resolveUserParams(
            toolName, userParam0, userParam1, userParam2);

        // Fire-and-forget: session creation (including PVC auto-creation) runs async.
        // Dashboard will show STARTING state with animation until READY.
        sm.tell(mgr -> mgr.createSession(sm, userId, toolName, null, userParams, storageType));
        LOG.info("Session start requested: user=" + userId + ", tool=" + toolName);

        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @POST
    @Path("/session/stop")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response stopSession(@FormParam("sessionId") String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Response.seeOther(URI.create("/dashboard?error=missing_session_id")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.destroySession(sessionId));
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @POST
    @Path("/session/memo")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateMemo(@FormParam("sessionId") String sessionId,
                               @FormParam("memo") String memo) {
        if (sessionId == null || sessionId.isBlank()) {
            return Response.seeOther(URI.create("/dashboard")).build();
        }
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.updateMemo(sessionId, memo != null ? memo.strip() : ""));
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/session/status")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSessionStatus() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        try {
            List<SessionStatus> statuses = sm.ask(mgr -> mgr.getUserSessions(userId)).get();
            if (statuses == null || statuses.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            sm.tell(mgr -> mgr.touchUserSessions(userId));
            return Response.ok(statuses).build();
        } catch (Exception e) {
            return Response.serverError().build();
        }
    }

    @GET
    @Path("/storage/info")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    public Response getStorageInfo() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Fire independent ask() calls in parallel
        var allPvcInfoFut = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId));
        var sessionsFut = sm.ask(mgr -> mgr.getUserSessions(userId));

        Map<String, Object> allPvcInfo = Collections.emptyMap();
        try {
            allPvcInfo = allPvcInfoFut.get();
        } catch (Exception e) {
            LOG.warning("Failed to load PVC info: " + e.getMessage());
        }

        List<Map<String, String>> activeSessions = Collections.emptyList();
        try {
            var sessions = sessionsFut.get();
            activeSessions = sessions.stream()
                .filter(s -> s.state() == SessionState.READY
                    || s.state() == SessionState.STARTING)
                .map(s -> Map.of(
                    "toolName", s.toolName(),
                    "podName", s.podName() != null ? s.podName() : "",
                    "storageType", s.storageType() != null ? s.storageType() : "",
                    "state", s.state().name()
                ))
                .toList();
        } catch (Exception e) {
            LOG.warning("Failed to load active sessions: " + e.getMessage());
        }

        // S3 storage info
        List<String> s3Files = Collections.emptyList();
        long s3Size = 0;
        try {
            s3Files = s3StorageClient.listUserFiles(userId);
            s3Size = s3StorageClient.getUserStorageSize(userId);
        } catch (Exception e) {
            LOG.warning("Failed to load S3 storage info: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("userId", userId);
        result.put("storageTypeOptions", actorSystem.getStorageTypeOptions());
        result.put("pvcInfo", allPvcInfo);
        result.put("activeSessions", activeSessions);
        result.put("s3Files", s3Files);
        result.put("s3Size", s3Size);
        return Response.ok(result).build();
    }

    @GET
    @Path("/storage/browse")
    @Authenticated
    public Response browseStorage() {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Check if file-browser session already exists
        try {
            List<SessionStatus> sessions = sm.ask(mgr -> mgr.getUserSessions(userId)).get();
            for (SessionStatus s : sessions) {
                if ("file-browser".equals(s.toolName())) {
                    if (s.state() == SessionState.READY && s.accessUrl() != null) {
                        return Response.seeOther(URI.create(s.accessUrl())).build();
                    }
                    // Starting or creating — go back to dashboard
                    return Response.seeOther(URI.create("/dashboard")).build();
                }
            }
        } catch (Exception e) {
            LOG.warning("Failed to check file-browser sessions: " + e.getMessage());
        }

        // No file-browser session — start one
        sm.tell(mgr -> mgr.createSession(sm, userId, "file-browser", null, Collections.emptyMap(), null));
        LOG.info("File browser start requested: user=" + userId);
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    @GET
    @Path("/storage/files")
    @Authenticated
    @Produces(MediaType.TEXT_HTML)
    public String showStorageFiles(@QueryParam("type") String type) {
        String userId = getCurrentUsername();
        String lang = "ja";

        Map<String, String> titles = Map.of(
            "s3upload", "S3 / アップロード",
            "s3public", "S3 / 公開",
            "fsupload", "ファイル / アップロード",
            "fspublic", "ファイル / 公開"
        );

        String title = titles.getOrDefault(type, "Storage");
        List<String> files = Collections.emptyList();
        long totalBytes = 0;

        if ("s3upload".equals(type)) {
            try {
                files = s3StorageClient.listUserFiles(userId);
                totalBytes = s3StorageClient.getUserStorageSize(userId);
            } catch (Exception e) {
                LOG.warning("Failed to list S3 upload files: " + e.getMessage());
            }
        } else if ("fsupload".equals(type)) {
            try {
                files = nfsStorageClient.listUserFiles(userId);
                totalBytes = nfsStorageClient.getUserStorageSize(userId);
            } catch (Exception e) {
                LOG.warning("Failed to list NFS upload files: " + e.getMessage());
            }
        }
        // s3public, fspublic: not yet implemented

        Map<String, Object> data = new HashMap<>();
        data.put("bp", basePath);
        data.put("lang", lang);
        data.put("userId", userId);
        data.put("title", title);
        data.put("files", files);
        data.put("fileCount", files.size());
        data.put("totalSize", formatSize(totalBytes));
        return storageFiles.data(data).render();
    }

    @POST
    @Path("/storage/pvc/create")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createPvc(@FormParam("storageType") String storageType) {
        String userId = getCurrentUsername();
        if (storageType == null || !actorSystem.getStorageTypeOptions().contains(storageType)) {
            return Response.seeOther(URI.create("/dashboard?error=invalid_storage_type")).build();
        }
        // Fire-and-forget: PVC creation runs async in actor thread
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.createUserPvc(userId, storageType, null));
        LOG.info("PVC creation requested: user=" + userId + ", type=" + storageType);
        return Response.seeOther(URI.create("/dashboard?creating=" + storageType)).build();
    }

    @POST
    @Path("/storage/pvc/delete")
    @Authenticated
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response deletePvc(@FormParam("storageType") String storageType,
                              @FormParam("confirmation") String confirmation) {
        String userId = getCurrentUsername();
        if (storageType == null || !actorSystem.getStorageTypeOptions().contains(storageType)) {
            return Response.seeOther(URI.create("/dashboard?error=invalid_storage_type")).build();
        }
        if (!"DELETE".equals(confirmation)) {
            return Response.seeOther(URI.create("/dashboard?error=confirmation_required")).build();
        }
        // Fire-and-forget: PVC deletion runs async in actor thread
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        sm.tell(mgr -> mgr.deleteUserPvc(userId, storageType));
        LOG.info("PVC deletion requested: user=" + userId + ", type=" + storageType);
        return Response.seeOther(URI.create("/dashboard")).build();
    }

    private String renderLanding(String lang) {
        boolean en = "en".equals(lang);
        String dashboardPath = en ? "/en/dashboard" : "/dashboard";
        String registerPath = en ? "/submission-account/en/register" : "/submission-account/register";
        String redirectUri = URLEncoder.encode(
            "https://192.168.5.25" + basePath + dashboardPath, StandardCharsets.UTF_8);
        String oidcAuthUrl = oidcAuthorizationEndpoint
            + "?client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
            + "&redirect_uri=" + redirectUri
            + "&response_type=code"
            + "&scope=openid";
        String langSwitchUrl = en ? basePath + "/" : basePath + "/en/";
        String langSwitchLabel = en ? "\u65e5\u672c\u8a9e" : "English";

        String heroDesc = en
            ? "Submit sequence data to INSDC databases through an integrated web interface.<br>"
              + "SRA, Assembly, Expression Data and more."
            : "DDBJ \u3078\u914d\u5217\u30c7\u30fc\u30bf\u3092\u767b\u9332\u3059\u308b\u305f\u3081\u306e\u30dd\u30fc\u30bf\u30eb\u3067\u3059\u3002<br>"
              + "SRA, Assembly, Expression Data \u306a\u3069\u306e\u30c7\u30fc\u30bf\u767b\u9332\u3092 Web \u30a4\u30f3\u30bf\u30d5\u30a7\u30a4\u30b9\u304b\u3089\u884c\u3048\u307e\u3059\u3002";
        String card1Title = en ? "Log in with Submission Account" : "Submission Account \u3067\u30ed\u30b0\u30a4\u30f3";
        String card1Desc = en
            ? "Log in with your existing DDBJ Submission Account."
            : "DDBJ Submission Account \u3092\u304a\u6301\u3061\u306e\u65b9\u306f\u3053\u3061\u3089\u304b\u3089\u30ed\u30b0\u30a4\u30f3\u3057\u3066\u304f\u3060\u3055\u3044\u3002";
        String card2Title = en ? "Log in with ORCID" : "ORCID \u3067\u30ed\u30b0\u30a4\u30f3";
        String card2Desc = en
            ? "Log in using your ORCID account."
            : "ORCID \u30a2\u30ab\u30a6\u30f3\u30c8\u3092\u4f7f\u7528\u3057\u3066\u30ed\u30b0\u30a4\u30f3\u3057\u307e\u3059\u3002";
        String card3Title = en ? "Create New Account" : "\u30a2\u30ab\u30a6\u30f3\u30c8\u65b0\u898f\u4f5c\u6210";
        String card3Desc = en
            ? "New users can create a Submission Account here."
            : "\u306f\u3058\u3081\u3066\u306e\u65b9\u306f\u3053\u3061\u3089\u304b\u3089\u65b0\u3057\u3044\u30a2\u30ab\u30a6\u30f3\u30c8\u3092\u4f5c\u6210\u3057\u3066\u304f\u3060\u3055\u3044\u3002";

        return ("""
            <!DOCTYPE html>
            <html lang="{{LANG}}">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>DDBJ Submission Portal</title>
            <link rel="stylesheet" href="{{BASE}}/css/style.css">
            </head>
            <body>
            <header class="header">
                <div class="header-content">
                    <h1>DDBJ Submission Portal</h1>
                    <div class="header-right">
                        <a href="{{LANG_SWITCH_URL}}" class="lang-link">{{LANG_SWITCH_LABEL}}</a>
                    </div>
                </div>
            </header>
            <main class="landing">
                <div class="landing-hero">
                    <h2>DDBJ Submission Portal</h2>
                    <p>{{HERO_DESC}}</p>
                </div>
                <div class="landing-features">
                    <a href="{{BASE}}{{DASHBOARD_PATH}}" class="feature-card feature-card-link">
                        <div class="feature-icon">&#x1f4dd;</div>
                        <h3>{{CARD1_TITLE}}</h3>
                        <p>{{CARD1_DESC}}</p>
                    </a>
                    <a href="{{ORCID_URL}}" class="feature-card feature-card-link">
                        <div class="feature-icon">&#x1f517;</div>
                        <h3>{{CARD2_TITLE}}</h3>
                        <p>{{CARD2_DESC}}</p>
                    </a>
                    <a href="https://192.168.5.25{{REGISTER_PATH}}" class="feature-card feature-card-link">
                        <div class="feature-icon">&#x1f4e4;</div>
                        <h3>{{CARD3_TITLE}}</h3>
                        <p>{{CARD3_DESC}}</p>
                    </a>
                </div>
            </main>
            </body>
            </html>
            """)
            .replace("{{LANG}}", lang)
            .replace("{{BASE}}", basePath)
            .replace("{{DASHBOARD_PATH}}", dashboardPath)
            .replace("{{REGISTER_PATH}}", registerPath)
            .replace("{{ORCID_URL}}", oidcAuthUrl + "&kc_idp_hint=orcid&prompt=login")
            .replace("{{LANG_SWITCH_URL}}", langSwitchUrl)
            .replace("{{LANG_SWITCH_LABEL}}", langSwitchLabel)
            .replace("{{HERO_DESC}}", heroDesc)
            .replace("{{CARD1_TITLE}}", card1Title)
            .replace("{{CARD1_DESC}}", card1Desc)
            .replace("{{CARD2_TITLE}}", card2Title)
            .replace("{{CARD2_DESC}}", card2Desc)
            .replace("{{CARD3_TITLE}}", card3Title)
            .replace("{{CARD3_DESC}}", card3Desc);
    }

    private String renderDashboard(String lang, String creating) {
        String userId = getCurrentUsername();
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();

        // Fire all independent ask() calls in parallel
        var toolsFuture = sm.ask(SessionManagerActor::listAvailableTools);
        var sessionsFuture = sm.ask(mgr -> mgr.getUserSessions(userId));
        var summaryFuture = sm.ask(SessionManagerActor::getSessionSummary);
        var allPvcInfoFuture = sm.ask(mgr -> mgr.getAllUserPvcInfo(userId));

        List<ToolPlugin> tools;
        try {
            tools = toolsFuture.get().stream()
                .filter(t -> !"file-browser".equals(t.name()))
                .toList();
        }
        catch (Exception e) { tools = Collections.emptyList(); }

        List<SessionStatus> userSessions;
        try { userSessions = sessionsFuture.get(); }
        catch (Exception e) { userSessions = Collections.emptyList(); }

        SessionSummary summary;
        try { summary = summaryFuture.get(); }
        catch (Exception e) { summary = new SessionSummary(0, 0, 0, 0); }

        Map<String, Object> allPvcInfo = Collections.emptyMap();
        try {
            allPvcInfo = allPvcInfoFuture.get();
        } catch (Exception e) {
            LOG.warning("Failed to load PVC info: " + e.getMessage());
        }

        // Auto-create nfs-k8s PVC if it does not exist yet
        if (creating == null || creating.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, String> nfsInfo = (Map<String, String>) allPvcInfo.get("nfs-k8s");
            boolean nfsExists = nfsInfo != null && "true".equals(nfsInfo.get("exists"));
            if (!nfsExists) {
                sm.tell(mgr -> mgr.createUserPvc(userId, "nfs-k8s", null));
                LOG.info("Auto-creating nfs-k8s PVC for user: " + userId);
                creating = "nfs-k8s";
            }
        }

        // S3 storage info
        List<String> s3Files = Collections.emptyList();
        long s3Size = 0;
        try {
            s3Files = s3StorageClient.listUserFiles(userId);
            s3Size = s3StorageClient.getUserStorageSize(userId);
        } catch (Exception e) {
            LOG.warning("Failed to load S3 storage info: " + e.getMessage());
        }

        // Past INSDC registrations
        List<InsdcRegistration> registrations = Collections.emptyList();
        try {
            registrations = registrationService.getUserRegistrations(userId);
        } catch (Exception e) {
            LOG.warning("Failed to load registrations: " + e.getMessage());
        }

        Map<String, Object> data = new HashMap<>();
        data.put("bp", basePath);
        data.put("lang", lang);
        data.put("langSwitchUrl", "en".equals(lang) ? basePath + "/dashboard" : basePath + "/en/dashboard");
        data.put("langSwitchLabel", "en".equals(lang) ? "\u65e5\u672c\u8a9e" : "English");
        data.put("userId", userId);
        data.put("tools", tools);
        data.put("sessions", userSessions);
        data.put("summary", summary);
        data.put("storageTypeOptions", actorSystem.getStorageTypeOptions());
        data.put("pvcInfo", allPvcInfo);
        data.put("s3UploadFiles", s3Files.size());
        data.put("s3UploadSize", formatSize(s3Size));
        data.put("s3PublicFiles", 0);
        data.put("s3PublicSize", formatSize(0));
        List<String> nfsFiles = Collections.emptyList();
        long nfsSize = 0;
        try {
            nfsFiles = nfsStorageClient.listUserFiles(userId);
            nfsSize = nfsStorageClient.getUserStorageSize(userId);
        } catch (Exception e) {
            LOG.warning("Failed to load NFS storage info: " + e.getMessage());
        }
        data.put("fsUploadFiles", nfsFiles.size());
        data.put("fsUploadSize", formatSize(nfsSize));
        data.put("fsPublicFiles", 0);
        data.put("fsPublicSize", formatSize(0));
        data.put("registrations", registrations);
        data.put("creating", creating != null ? creating : "");

        // Check if any session is still starting (for auto-refresh)
        boolean hasStarting = userSessions.stream()
            .anyMatch(s -> s.state() == SessionState.CREATING || s.state() == SessionState.STARTING);
        data.put("hasStarting", hasStarting ? "true" : "false");

        return dashboard.data(data).render();
    }

    /**
     * Maps form field values (userParam_0, _1, _2) to env var names
     * defined in the tool's userParameters().
     */
    private Map<String, String> resolveUserParams(String toolName,
                                                   String... values) {
        ActorRef<SessionManagerActor> sm = actorSystem.getSessionManager();
        List<ToolPlugin> tools;
        try {
            tools = sm.ask(SessionManagerActor::listAvailableTools).get();
        } catch (Exception e) {
            return Collections.emptyMap();
        }

        ToolPlugin plugin = null;
        for (ToolPlugin t : tools) {
            if (t.name().equals(toolName)) {
                plugin = t;
                break;
            }
        }
        if (plugin == null) {
            return Collections.emptyMap();
        }

        List<UserParameter> params = plugin.userParameters();
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < params.size() && i < values.length; i++) {
            if (values[i] != null && !values[i].isBlank()) {
                result.put(params.get(i).envVarName(), values[i]);
            }
        }
        return result;
    }

    private String toJsonArray(List<String> items) {
        if (items == null || items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")).append("\"");
        }
        return sb.append("]").toString();
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int idx = 0;
        double size = bytes;
        while (size >= 1024 && idx < units.length - 1) {
            size /= 1024;
            idx++;
        }
        if (idx == 0) return bytes + " B";
        return String.format("%.1f %s", size, units[idx]);
    }

    private String getCurrentUsername() {
        try {
            if (idToken != null) {
                String username = idToken.getClaim("preferred_username");
                if (username != null) {
                    return username;
                }
            }
        } catch (Exception e) {
            // Fallback to SecurityIdentity
        }
        if (identity != null && !identity.isAnonymous()) {
            return identity.getPrincipal().getName();
        }
        return null;
    }
}
