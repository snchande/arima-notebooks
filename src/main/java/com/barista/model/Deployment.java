package com.barista.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A record of one definition Arima wrote to disk outside the notebook store.
 *
 * Kept so a deploy is reversible: {@link com.barista.service.DeploymentService} removes exactly
 * the paths it recorded here and nothing else. Persisted as a JSON array in
 * {@code data/deployments.json}, so this is a mutable bean rather than a record (Jackson
 * round-trips it by field).
 */
public class Deployment {

    private String id;                  // definition notebook id
    private String kind;                // agent | skill | tool | plugin
    private String slug;                // deployed name
    private String target;              // project | user | bundle
    private String provider;            // claude | copilot | gemini (agents & skills)
    private List<String> paths = new ArrayList<>();   // absolute paths written
    private String deployedAt;          // ISO-8601

    public Deployment() {}

    public Deployment(String id, String kind, String slug, String target,
                      String provider, List<String> paths, String deployedAt) {
        this.id = id;
        this.kind = kind;
        this.slug = slug;
        this.target = target;
        this.provider = provider;
        this.paths = paths == null ? new ArrayList<>() : new ArrayList<>(paths);
        this.deployedAt = deployedAt;
    }

    /** Deployments are identified by the definition and where it went. */
    public String key() { return id + "@" + target; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths == null ? new ArrayList<>() : paths; }

    public String getDeployedAt() { return deployedAt; }
    public void setDeployedAt(String deployedAt) { this.deployedAt = deployedAt; }
}
