package com.ossature.interviewer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** {@link RestClient} pointed at the Python LangGraph service (PRD 6.1). */
@Configuration
public class AgentClientConfig {

    @Bean
    RestClient agentRestClient(AppProperties props, RestClient.Builder builder) {
        return builder.baseUrl(props.agent().baseUrl()).build();
    }
}
