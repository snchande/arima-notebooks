package com.barista.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerInfoServiceTest {

    @Test
    void humanizeUptimeShowsSecondsOnlyUnderAMinute() {
        assertEquals("0s", ServerInfoService.humanizeUptime(0));
        assertEquals("18s", ServerInfoService.humanizeUptime(18_238));
        assertEquals("59s", ServerInfoService.humanizeUptime(59_999));
    }

    @Test
    void humanizeUptimeAddsCoarserUnitsAsItGrows() {
        assertEquals("1m 0s", ServerInfoService.humanizeUptime(60_000));
        assertEquals("2m 5s", ServerInfoService.humanizeUptime(125_000));
        assertEquals("1h 0m 0s", ServerInfoService.humanizeUptime(3_600_000));
        assertEquals("3h 14m 22s", ServerInfoService.humanizeUptime(11_662_000));
    }

    @Test
    void humanizeUptimeKeepsZeroedUnitsOnceDaysAppear() {
        assertEquals("1d 0h 0m 0s", ServerInfoService.humanizeUptime(86_400_000));
        assertEquals("2d 3h 4m 5s", ServerInfoService.humanizeUptime(183_845_000));
    }
}
