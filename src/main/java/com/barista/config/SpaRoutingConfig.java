package com.barista.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deep links for the single-page UI.
 *
 * Arima Notebooks is one page served from {@code static/index.html}, so before this
 * the only address was {@code /} — a notebook could not be linked to or shared. These
 * routes forward the shareable URLs back to that page; the frontend then reads the
 * path and opens the right notebook and cell.
 *
 * <pre>
 *   /notebooks/{notebookId}                     open a notebook
 *   /notebooks/{notebookId}/cells/{cellId}      open it and focus one cell
 * </pre>
 *
 * These are UI routes and deliberately distinct from the REST API, which lives under
 * {@code /api/notebooks/**} and is untouched — forwarding happens only for the
 * browser-facing paths, so {@code GET /api/notebooks/{id}} still returns JSON.
 */
@Configuration
public class SpaRoutingConfig {

    @Bean
    public WebMvcConfigurer spaRoutingConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/notebooks/{notebookId}")
                        .setViewName("forward:/index.html");
                registry.addViewController("/notebooks/{notebookId}/cells/{cellId}")
                        .setViewName("forward:/index.html");
            }
        };
    }
}
