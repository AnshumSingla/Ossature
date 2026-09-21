package com.ossature.interviewer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** {@link RestClient} pointed at the Python LangGraph service (PRD 6.1). */
@Configuration
public class AgentClientConfig {

    // Explicit request factory: RestClient.builder().build()'s auto-detected default here
    // is a streaming, chunked-transfer client, and POST bodies sent through it silently
    // never reached the agent (FastAPI/uvicorn saw an empty body - confirmed by switching
    // factories and watching the same request start succeeding). SimpleClientHttpRequestFactory
    // buffers the body and sends a real Content-Length, which uvicorn's HTTP parser handles
    // correctly.
    @Bean
    RestClient agentRestClient(AppProperties props) {
        return RestClient.builder()
                .baseUrl(props.agent().baseUrl())
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }
}
