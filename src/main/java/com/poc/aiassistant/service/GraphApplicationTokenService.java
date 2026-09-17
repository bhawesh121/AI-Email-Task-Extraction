package com.poc.aiassistant.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Service;

@Service
public class GraphApplicationTokenService {

    private static final String CLIENT_REGISTRATION_ID =
            "microsoft-admin";

    private static final String PRINCIPAL_NAME =
            "aiassistant-tenant-admin";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public GraphApplicationTokenService(
            @Qualifier("applicationAuthorizedClientManager")
            OAuth2AuthorizedClientManager authorizedClientManager
    ) {
        this.authorizedClientManager =
                authorizedClientManager;
    }

    public String getAccessToken() {

        OAuth2AuthorizeRequest authorizeRequest =
                OAuth2AuthorizeRequest
                        .withClientRegistrationId(
                                CLIENT_REGISTRATION_ID
                        )
                        .principal(
                                PRINCIPAL_NAME
                        )
                        .build();

        OAuth2AuthorizedClient authorizedClient =
                authorizedClientManager.authorize(
                        authorizeRequest
                );

        if (authorizedClient == null) {
            throw new IllegalStateException(
                    "Unable to obtain application-only Microsoft Graph access token."
            );
        }

        if (authorizedClient.getAccessToken() == null) {
            throw new IllegalStateException(
                    "Microsoft Graph application access token is unavailable."
            );
        }

        return authorizedClient
                .getAccessToken()
                .getTokenValue();
    }
}