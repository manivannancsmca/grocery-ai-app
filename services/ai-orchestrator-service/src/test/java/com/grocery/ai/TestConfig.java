package com.grocery.ai;

import com.grocery.ai.model.ScriptedChatModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** The adjustable clock and the switchable model of the integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TestConfig {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }

    @Bean
    @Primary
    TestModels.Switchable switchableChatModel(ScriptedChatModel scripted) {
        return new TestModels.Switchable(scripted);
    }
}
