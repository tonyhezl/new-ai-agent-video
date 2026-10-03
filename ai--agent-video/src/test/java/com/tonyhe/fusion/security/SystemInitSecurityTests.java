package com.tonyhe.fusion.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = SystemInitSecurityTests.SetupController.class,
        properties = "app.cors.allowed-origin-patterns=https://video.example.com")
@Import({
        SecurityConfig.class,
        SystemInitSecurityTests.TestFilterConfig.class,
        SystemInitSecurityTests.SetupController.class
})
class SystemInitSecurityTests {

    private static final String SETUP_BODY = """
            {"username":"admin","password":"123456","nickname":"Admin"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousSetupIsPublic() throws Exception {
        mockMvc.perform(post("/api/system/init/setup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SETUP_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void configuredProductionOriginIsAllowed() throws Exception {
        mockMvc.perform(post("/api/system/init/setup")
                        .header("Origin", "https://video.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SETUP_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://video.example.com"));
    }

    @Test
    void unknownOriginIsRejected() throws Exception {
        mockMvc.perform(post("/api/system/init/setup")
                        .header("Origin", "https://unknown.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SETUP_BODY))
                .andExpect(status().isForbidden());
    }

    @Configuration(proxyBeanMethods = false)
    static class TestFilterConfig {

        @Bean
        TokenAuthenticationFilter tokenAuthenticationFilter() {
            return new TokenAuthenticationFilter(null, null);
        }

        @Bean
        MediaAccessFilter mediaAccessFilter() {
            return new MediaAccessFilter(null, null);
        }
    }

    @RestController
    @RequestMapping("/api/system/init")
    static class SetupController {

        @PostMapping("/setup")
        String setup() {
            return "ok";
        }
    }
}
