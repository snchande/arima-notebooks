package com.barista.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Arima executes code as the user who started it and does not authenticate callers
 * in local mode, so reachability is the entire security boundary. These assertions
 * exist to make a regression here loud rather than silent.
 */
class LocalAccessFilterTest {

    @Test
    @DisplayName("loopback peers are allowed, everything else is not")
    void peerAddresses() {
        assertTrue(LocalAccessFilter.isLoopback("127.0.0.1"));
        assertTrue(LocalAccessFilter.isLoopback("::1"));
        assertTrue(LocalAccessFilter.isLoopback("0:0:0:0:0:0:0:1"));
        assertTrue(LocalAccessFilter.isLoopback("127.0.0.53"), "all of 127/8 is loopback");

        assertFalse(LocalAccessFilter.isLoopback("192.168.1.11"), "a LAN peer must be refused");
        assertFalse(LocalAccessFilter.isLoopback("10.0.0.5"));
        assertFalse(LocalAccessFilter.isLoopback("0.0.0.0"));
        assertFalse(LocalAccessFilter.isLoopback(null));
        assertFalse(LocalAccessFilter.isLoopback(""));
    }

    @Test
    @DisplayName("Host must name this machine, so DNS rebinding cannot use a local socket")
    void hostHeader() {
        assertTrue(LocalAccessFilter.isLoopbackHost("localhost:8585"));
        assertTrue(LocalAccessFilter.isLoopbackHost("127.0.0.1:8585"));
        assertTrue(LocalAccessFilter.isLoopbackHost("[::1]:8585"));
        assertTrue(LocalAccessFilter.isLoopbackHost("LOCALHOST"), "case must not matter");
        assertTrue(LocalAccessFilter.isLoopbackHost("arima.localhost:8585"));

        // The rebinding case: the socket is loopback, but the page asked for a name
        // the attacker controls.
        assertFalse(LocalAccessFilter.isLoopbackHost("evil.example.com"));
        assertFalse(LocalAccessFilter.isLoopbackHost("evil.example.com:8585"));
        assertFalse(LocalAccessFilter.isLoopbackHost("192.168.1.11:8585"));

        // Absent Host is fine - the peer check already proved locality, and a browser
        // always sends one, so the rebinding path stays covered.
        assertTrue(LocalAccessFilter.isLoopbackHost(null));
        assertTrue(LocalAccessFilter.isLoopbackHost(""));
    }

    @Test
    @DisplayName("a hostname that merely looks local is still refused")
    void lookalikeHosts() {
        assertFalse(LocalAccessFilter.isLoopbackHost("localhost.evil.com"));
        assertFalse(LocalAccessFilter.isLoopbackHost("notlocalhost"));
        assertFalse(LocalAccessFilter.isLoopbackHost("[::1].evil.com"));
    }
}
