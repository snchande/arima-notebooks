package com.barista.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.barista.model.AuthProvider;
import com.barista.model.UserProfile;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;

/**
 * Resolves the currently authenticated user.
 *
 * LOCAL mode  — automatically uses the OS login name; no password required.
 * OAUTH mode  — resolved from Spring Security's OAuth2AuthenticationToken.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Value("${barista.auth.mode:local}")
    private String authMode;

    @Value("${barista.data.dir:data}")
    private String dataDir;

    private final ObjectMapper objectMapper;

    public UserService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(dataDir, "users"));
    }

    public boolean isLocalMode() {
        return !"oauth".equalsIgnoreCase(authMode);
    }

    /**
     * Returns the current user or null (only null in oauth mode when not authenticated).
     */
    public UserProfile getCurrentUser() {
        if (isLocalMode()) {
            return loadOrCreateLocalUser();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof OAuth2AuthenticationToken token) {
            return loadOrCreateOAuthUser(token);
        }
        return null;
    }

    /** Stable local user ID derived from OS username. */
    public String getLocalUserId() {
        String osUser = System.getProperty("user.name", "user");
        return "local-" + sanitize(osUser);
    }

    /** Update email address for a user profile. */
    public UserProfile updateEmail(String userId, String email) throws IOException {
        UserProfile user = loadUserFromDisk(userId);
        if (user == null) user = getCurrentUser();
        if (user == null) throw new IllegalStateException("User not found: " + userId);
        user.setEmail(email);
        user.setLastSeenAt(LocalDateTime.now());
        saveUserToDisk(user);
        return user;
    }

    // ── Private helpers ──────────────────────────────────────────

    private UserProfile loadOrCreateLocalUser() {
        String userId = getLocalUserId();
        UserProfile existing = loadUserFromDisk(userId);
        if (existing != null) {
            // A profile saved before Arima asked the OS for a display name still holds
            // the capitalised login name. Upgrade it, but only when it is exactly that,
            // so anything the user has set themselves is left alone.
            String osUserName = System.getProperty("user.name", "User");
            if (capitalize(osUserName).equals(existing.getName())) {
                String full = resolveOsFullName(osUserName);
                if (!full.equals(existing.getName())) existing.setName(full);
            }
            existing.setLastSeenAt(LocalDateTime.now());
            try { saveUserToDisk(existing); } catch (IOException ignore) {}
            return existing;
        }

        String osUser = System.getProperty("user.name", "User");
        UserProfile profile = UserProfile.builder()
                .id(userId)
                .name(resolveOsFullName(osUser))
                .authProvider(AuthProvider.LOCAL)
                .createdAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
        try { saveUserToDisk(profile); } catch (IOException e) {
            log.warn("Could not persist local user profile: {}", e.getMessage());
        }
        return profile;
    }

    /**
     * The person's actual name, not their login name.
     *
     * <p>{@code user.name} gives the account ("sures"), which reads oddly in a UI that
     * greets you. Each OS records a display name somewhere different, so ask it, and
     * fall back to the capitalised login name when it is not available - that is only
     * cosmetic, so it must never fail loudly or hold up startup.
     */
    static String resolveOsFullName(String osUser) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                String out = runQuietly(6, "net", "user", osUser);
                for (String line : out.split("\\r?\\n")) {
                    if (line.toLowerCase().startsWith("full name")) {
                        String name = line.substring("full name".length()).trim();
                        if (!name.isBlank()) return name;
                    }
                }
            } else if (os.contains("mac")) {
                String out = runQuietly(6, "id", "-F");
                if (!out.isBlank()) return out.trim();
            } else {
                // GECOS: user:x:uid:gid:Full Name,,,:/home/user:/bin/bash
                String out = runQuietly(6, "getent", "passwd", osUser);
                String[] parts = out.split(":");
                if (parts.length > 4) {
                    String gecos = parts[4].split(",")[0].trim();
                    if (!gecos.isBlank()) return gecos;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the OS display name: {}", e.getMessage());
        }
        return capitalize(osUser);
    }

    private static String runQuietly(int timeoutSec, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(java.util.List.of(command));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return p.exitValue() == 0 ? out : "";
    }

    private UserProfile loadOrCreateOAuthUser(OAuth2AuthenticationToken token) {
        OAuth2User oauthUser = token.getPrincipal();
        String registrationId = token.getAuthorizedClientRegistrationId();
        AuthProvider provider = providerFromRegistrationId(registrationId);

        // Derive consistent user ID: provider-{sub/id}
        String sub = getAttribute(oauthUser, "sub", "id");
        String userId = registrationId + "-" + sub;

        UserProfile existing = loadUserFromDisk(userId);
        if (existing != null) {
            // Refresh mutable fields from provider each login
            existing.setName(resolveName(oauthUser, provider));
            String providerEmail = getAttribute(oauthUser, "email");
            if (providerEmail != null && (existing.getEmail() == null || existing.getEmail().isBlank())) {
                existing.setEmail(providerEmail);
            }
            existing.setAvatarUrl(resolveAvatarUrl(oauthUser, provider));
            existing.setLastSeenAt(LocalDateTime.now());
            try { saveUserToDisk(existing); } catch (IOException ignore) {}
            return existing;
        }

        UserProfile profile = UserProfile.builder()
                .id(userId)
                .name(resolveName(oauthUser, provider))
                .email(getAttribute(oauthUser, "email"))
                .avatarUrl(resolveAvatarUrl(oauthUser, provider))
                .authProvider(provider)
                .createdAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
        try { saveUserToDisk(profile); } catch (IOException e) {
            log.warn("Could not persist OAuth user profile: {}", e.getMessage());
        }
        return profile;
    }

    private String resolveName(OAuth2User user, AuthProvider provider) {
        // Try provider-specific name attributes first
        String name = switch (provider) {
            case MICROSOFT -> getAttribute(user, "displayName", "name");
            case FACEBOOK  -> getAttribute(user, "name");
            default        -> getAttribute(user, "name");
        };
        return name != null ? name : "User";
    }

    private String resolveAvatarUrl(OAuth2User user, AuthProvider provider) {
        return switch (provider) {
            case GOOGLE    -> getAttribute(user, "picture");
            case FACEBOOK  -> {
                // Facebook picture is nested: { data: { url: "..." } }
                Object pic = user.getAttribute("picture");
                if (pic instanceof java.util.Map<?,?> picMap) {
                    Object data = picMap.get("data");
                    if (data instanceof java.util.Map<?,?> dataMap) {
                        Object url = dataMap.get("url");
                        yield url != null ? url.toString() : null;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    private UserProfile loadUserFromDisk(String userId) {
        Path path = userPath(userId);
        if (!Files.exists(path)) return null;
        try {
            return objectMapper.readValue(path.toFile(), UserProfile.class);
        } catch (IOException e) {
            log.warn("Could not read user profile {}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void saveUserToDisk(UserProfile profile) throws IOException {
        Files.createDirectories(Paths.get(dataDir, "users"));
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(userPath(profile.getId()).toFile(), profile);
    }

    private Path userPath(String userId) {
        return Paths.get(dataDir, "users", userId + ".json");
    }

    private static AuthProvider providerFromRegistrationId(String id) {
        return switch (id.toLowerCase()) {
            case "google"    -> AuthProvider.GOOGLE;
            case "microsoft" -> AuthProvider.MICROSOFT;
            case "facebook"  -> AuthProvider.FACEBOOK;
            default          -> AuthProvider.LOCAL;
        };
    }

    private static String getAttribute(OAuth2User user, String... keys) {
        for (String key : keys) {
            Object val = user.getAttribute(key);
            if (val != null) return val.toString();
        }
        return null;
    }

    private static String sanitize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
