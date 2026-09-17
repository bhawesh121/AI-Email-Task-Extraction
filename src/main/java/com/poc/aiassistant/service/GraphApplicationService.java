package com.poc.aiassistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GraphApplicationService {

    private final GraphApplicationTokenService tokenService;
    private final RestClient restClient;

    public GraphApplicationService(
            GraphApplicationTokenService tokenService,
            @Value("${microsoft.graph.base-url}") String graphBaseUrl
    ) {

        this.tokenService = tokenService;

        this.restClient = RestClient.builder()
                .baseUrl(graphBaseUrl)
                .build();
    }

    public String getUsers() {

        String accessToken =
                tokenService.getAccessToken();

        return restClient
                .get()
                .uri(
                        "/users?$select=id,displayName,mail,userPrincipalName"
                )
                .header(
                        "Authorization",
                        "Bearer " + accessToken
                )
                .retrieve()
                .body(String.class);
    }
}