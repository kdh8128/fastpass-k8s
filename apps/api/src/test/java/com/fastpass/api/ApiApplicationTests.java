package com.fastpass.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
                "fastpass.redis-state-initializer.enabled=false"
        }
)
class ApiApplicationTests {

    @Test
    void contextLoads() {
    }
}