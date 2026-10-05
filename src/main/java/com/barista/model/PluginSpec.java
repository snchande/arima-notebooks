package com.barista.model;

import java.util.List;

/**
 * A bundle of agents, skills and tools that deploys as one unit.
 *
 * A plugin notebook is a normal {@link Notebook} with {@code metadata.kind = "plugin"} and a
 * {@code metadata.plugin} block listing member definition ids;
 * {@link com.barista.service.PluginService} projects it into this shape and builds a Claude Code
 * plugin directory from it.
 *
 * @param id          notebook id
 * @param name        display name
 * @param slug        kebab-cased name — the deployed directory name
 * @param description one-line description (plugin.json description)
 * @param version     semantic version string (plugin.json version)
 * @param author      author string (plugin.json author)
 * @param members     ids of the agent / skill / tool notebooks this plugin ships
 * @param readme      the plugin notebook's markdown cells — becomes README.md
 */
public record PluginSpec(String id, String name, String slug, String description,
                         String version, String author, List<String> members, String readme) {
}
