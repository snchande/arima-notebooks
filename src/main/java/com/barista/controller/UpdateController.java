package com.barista.controller;

import com.barista.service.UpdateService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * "Is there a newer version, and will you install it."
 *
 * <p>Checking is read-only and safe to call from the UI on load. Applying an update
 * pulls, rebuilds and restarts, so it is a deliberate POST from the machine running
 * Arima - never something a remote caller can trigger, even with network access on.
 */
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    private final UpdateService updateService;
    private final SystemController systemController;

    public UpdateController(UpdateService updateService, SystemController systemController) {
        this.updateService = updateService;
        this.systemController = systemController;
    }

    /** Whether master is ahead, and what changed. Never modifies anything. */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestParam(defaultValue = "false") boolean force) {
        return ResponseEntity.ok(updateService.check(force));
    }

    /** Progress of an update in flight. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(updateService.status());
    }

    /**
     * Pull, rebuild, restart. The restart reuses the existing lifecycle path, so the
     * launcher relaunches exactly as it does for a normal restart.
     */
    @PostMapping("/apply")
    public ResponseEntity<?> apply(HttpServletRequest req) {
        if (!com.barista.config.LocalAccessFilter.isLoopback(req.getRemoteAddr())) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Only the machine running Arima can install an update."));
        }
        String error = updateService.applyUpdate(systemController::restartForUpdate);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("error", error));
        }
        return ResponseEntity.ok(Map.of(
                "status", "updating",
                "message", "Pulling, rebuilding and restarting. This page will reconnect."));
    }
}
