package com.barista;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
public class BaristaApplication {

    public static void main(String[] args) {
        applyNetworkBinding();
        SpringApplication.run(BaristaApplication.class, args);
    }

    /**
     * Decide which interface to listen on, before Spring binds the connector.
     *
     * <p>Arima executes code as the user who started it and does not authenticate
     * callers, so the default is loopback and the port is simply not open anywhere
     * else. Opening it to the local network is a deliberate choice the user makes in
     * Settings, and it only takes effect on the next start - which is the honest
     * behaviour anyway, since a listening socket cannot be re-bound underneath a
     * running server.
     *
     * <p>Read straight from settings.json rather than through SettingsService: this
     * runs before the Spring context exists.
     */
    private static void applyNetworkBinding() {
        boolean allowNetwork = false;
        try {
            Path settings = Paths.get(System.getProperty("barista.data.dir", "data"), "settings.json");
            if (Files.exists(settings)) {
                JsonNode node = new ObjectMapper().readTree(settings.toFile());
                JsonNode flag = node.get("networkAccessEnabled");
                allowNetwork = flag != null && flag.asBoolean(false);
            }
        } catch (Exception e) {
            // An unreadable or malformed settings file must never widen the bind.
            allowNetwork = false;
        }

        if (allowNetwork) {
            System.setProperty("barista.bind.address", "0.0.0.0");
            System.out.println("""
                    ============================================================
                     NETWORK ACCESS IS ON
                     Arima is reachable from your local network. It executes code
                     with your user account. Work arriving from another machine is
                     held for your approval before it runs.
                     Turn this off in Settings if you did not intend it.
                    ============================================================""");
        } else {
            System.setProperty("barista.bind.address", "127.0.0.1");
        }
    }
}
