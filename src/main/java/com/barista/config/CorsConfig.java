package com.barista.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for local development.
 * Allows the frontend to call the REST API from any origin.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // NOT "*". A wildcard origin let any website you happened to be
                // visiting POST to /api/shell/execute on your own machine and run
                // code, with CSRF disabled - a drive-by, without needing the LAN at
                // all. Only pages served by Arima itself are allowed.
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(
                                "http://localhost:[*]", "http://127.0.0.1:[*]",
                                "http://[::1]:[*]")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false);
            }
        };
    }
}
