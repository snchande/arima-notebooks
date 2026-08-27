package com.barista.controller;

import com.barista.service.ServerInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SystemController.class)
@Import(SystemControllerTest.NoSecurity.class)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ServerInfoService serverInfoService;

    @MockBean
    private ConfigurableApplicationContext context;

    @Test
    void infoReturnsServiceMetadata() throws Exception {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Arima Notebooks");
        info.put("status", "running");
        info.put("version", "4.0.1");
        info.put("uptime", "3h 14m 22s");
        info.put("pid", 30104);
        given(serverInfoService.getInfo()).willReturn(info);

        mockMvc.perform(get("/api/system/info"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("Arima Notebooks"))
               .andExpect(jsonPath("$.status").value("running"))
               .andExpect(jsonPath("$.uptime").value("3h 14m 22s"))
               .andExpect(jsonPath("$.pid").value(30104));
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class NoSecurity {
        @org.springframework.context.annotation.Bean
        org.springframework.security.web.SecurityFilterChain chain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }
    }
}
