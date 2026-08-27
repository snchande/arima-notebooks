package com.barista.controller;

import com.barista.service.ApprovalService;
import com.barista.service.SettingsService;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The local user's side of the approval flow.
 *
 * <p>Work that arrived from another machine is listed here and released - or refused -
 * by the person sitting at the machine running Arima. When something arrives, the
 * browser is brought to the front with it on screen, so approval is an explicit act
 * rather than something noticed later in a log.
 */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final SettingsService settingsService;

    @Value("${server.port:8585}")
    private int port;

    public ApprovalController(ApprovalService approvalService, SettingsService settingsService) {
        this.approvalService = approvalService;
        this.settingsService = settingsService;
    }

    @PostConstruct
    void raiseTheWindowOnArrival() {
        approvalService.onNewRequest(() ->
                ApprovalService.raiseBrowser("http://localhost:" + port + "/?review=1"));
    }

    /** Everything currently waiting on the user. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> pending() {
        List<Map<String, Object>> items = approvalService.listPending();
        return ResponseEntity.ok(Map.of(
                "networkAccessEnabled", settingsService.getSettings().isNetworkAccessEnabled(),
                "pending", items,
                "count", items.size()));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable String id, HttpServletRequest req) {
        if (notLocal(req)) return refuseRemote();
        return decided(approvalService.decide(id, true), id, "approved");
    }

    @PostMapping("/{id}/deny")
    public ResponseEntity<?> deny(@PathVariable String id, HttpServletRequest req) {
        if (notLocal(req)) return refuseRemote();
        return decided(approvalService.decide(id, false), id, "denied");
    }

    /** Refuse everything outstanding in one action. */
    @PostMapping("/deny-all")
    public ResponseEntity<?> denyAll(HttpServletRequest req) {
        if (notLocal(req)) return refuseRemote();
        approvalService.denyAll();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Approval is the whole point of the gate, so only the machine running Arima may
     * grant it. Without this a remote caller could approve its own code.
     */
    private boolean notLocal(HttpServletRequest req) {
        return !com.barista.config.LocalAccessFilter.isLoopback(req.getRemoteAddr());
    }

    private ResponseEntity<?> refuseRemote() {
        return ResponseEntity.status(403).body(Map.of(
                "error", "Only the machine running Arima can approve or refuse a request."));
    }

    private ResponseEntity<?> decided(boolean found, String id, String what) {
        if (!found) {
            // Already answered, or it waited too long and was refused on expiry.
            return ResponseEntity.status(410)
                    .body(Map.of("error", "That request is no longer waiting", "id", id));
        }
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "state", what));
    }
}
