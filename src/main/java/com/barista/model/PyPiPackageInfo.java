package com.barista.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A PyPI package installed for Python cells.
 *
 * {@code paths} records the top-level entries the install added under the target
 * site directory (import package(s) + dist-info), so an uninstall can delete
 * exactly what was added — pip's own {@code uninstall} does not support
 * {@code --target} installs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PyPiPackageInfo {

    private String name;
    private String version;
    private LocalDateTime installedAt = LocalDateTime.now();
    private List<String> paths = new ArrayList<>();

    public PyPiPackageInfo() {}

    public PyPiPackageInfo(String name, String version, LocalDateTime installedAt, List<String> paths) {
        this.name = name;
        this.version = version;
        this.installedAt = installedAt;
        if (paths != null) this.paths = paths;
    }

    public String getId()          { return name + "==" + version; }
    public String getDisplayName() { return name + " " + version; }

    public String getName()    { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public LocalDateTime getInstalledAt() { return installedAt; }
    public void setInstalledAt(LocalDateTime v) { this.installedAt = v; }

    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths == null ? new ArrayList<>() : paths; }
}
