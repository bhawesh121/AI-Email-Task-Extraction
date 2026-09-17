package com.poc.aiassistant.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.JdbcOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthenticatedPrincipalOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.poc.aiassistant.security.ApiAuthenticationEntryPoint;

@Configuration
public class SecurityConfig {

    private final String frontendBaseUrl;

    public SecurityConfig(
            @Value("${app.frontend-base-url:http://localhost:5173}")
            String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    /**
     * ROOT CAUSE FIX for the intermittent "/login?error" on first sign-in.
     *
     * Confirmed via production logs on 2026-09-14: Microsoft correctly
     * returns an authorization code, the code exchange succeeds, but ID
     * token verification then fails with:
     *
     *   invalid_id_token: ... GET https://login.microsoftonline.com/
     *   .../discovery/v2.0/keys: Read timed out
     *
     * Spring Security's default HTTP client for fetching the JWK set
     * (used to verify the ID token's signature) carries very short
     * legacy Nimbus default timeouts - far too aggressive for a real
     * network round trip to Microsoft's endpoint from inside a Docker
     * container. The very next login attempt "just works" only because
     * the JWK set is cached in memory the moment a fetch succeeds -
     * this is exactly why the failure looked intermittent and
     * session/state-related from the outside, when it was actually a
     * plain network timeout.
     *
     * Fix: give that HTTP client the same realistic timeouts already
     * used elsewhere in this app (see litellm.*.connect-timeout-ms /
     * read-timeout-ms in application.yaml) instead of the ~few-hundred-
     * millisecond legacy default.
     */
        @Bean
        public JwtDecoderFactory<ClientRegistration> jwtDecoderFactory() {

        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(10_000);

        RestTemplate restTemplate = new RestTemplate(requestFactory);

        return clientRegistration -> {
                String issuerUri =
                        clientRegistration.getProviderDetails().getIssuerUri();

                NimbusJwtDecoder decoder =
                        NimbusJwtDecoder
                                .withIssuerLocation(issuerUri)
                                .restOperations(restTemplate)
                                .build();

                decoder.setJwtValidator(
                        JwtValidators.createDefaultWithValidators(
                                new OidcIdTokenValidator(clientRegistration)
                        )
                );

                decoder.setClaimSetConverter(
                        OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter()
                );

                return decoder;
        };
        }

    @Bean
    public JdbcOperations jdbcOperations(
            DataSource dataSource) {

        return new JdbcTemplate(dataSource);
    }

    @Bean
    public OAuth2AuthorizedClientService authorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository,
            JdbcOperations jdbcOperations) {

        return new JdbcOAuth2AuthorizedClientService(
                jdbcOperations,
                clientRegistrationRepository
        );
    }

    @Bean
    public OAuth2AuthorizedClientRepository authorizedClientRepository(
            OAuth2AuthorizedClientService authorizedClientService) {

        return new AuthenticatedPrincipalOAuth2AuthorizedClientRepository(
                authorizedClientService
        );
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        OAuth2AuthorizedClientProvider provider =
                OAuth2AuthorizedClientProviderBuilder
                        .builder()
                        .refreshToken()
                        .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository,
                        authorizedClientService
                );

        manager.setAuthorizedClientProvider(provider);

        return manager;
    }

    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {

        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(
                        clientRegistrationRepository,
                        OAuth2AuthorizationRequestRedirectFilter
                                .DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
                );

        /*
         * Force a real credential prompt on every "Continue with
         * Microsoft" click, even when the browser still has an active
         * Microsoft SSO session.
         *
         * This matters because logoutSuccessHandler() below is
         * deliberately local-only now (no more round-trip through
         * Microsoft's own "pick an account" / "you signed out" pages -
         * see its javadoc). That means signing out of THIS app does
         * NOT end the browser's underlying Microsoft session, so
         * without this, clicking "Continue with Microsoft" again would
         * silently re-authenticate via that still-active SSO session
         * with no prompt at all - which is the "it should reauthenticate"
         * behaviour that was missing.
         */
        resolver.setAuthorizationRequestCustomizer(
                customizer -> customizer.additionalParameters(
                        params -> params.put("prompt", "login")
                )
        );

        return resolver;
    }

    private static final Logger OAUTH2_LOGIN_LOGGER =
            LoggerFactory.getLogger("com.poc.aiassistant.security.OAuth2LoginFailure");

    /**
     * TEMPORARY DIAGNOSTIC HANDLER.
     *
     * Every OAuth2 login failure currently disappears silently into a
     * "/login?error" redirect with no trace of WHY it failed - there is
     * no org.springframework.security logging configured anywhere in
     * this app, so failures are invisible in production logs.
     *
     * This handler logs the real exception (class, message, and for
     * OAuth2AuthenticationException specifically, the OAuth2 error
     * code/description Spring Security assigned) at ERROR level -
     * guaranteed visible regardless of the configured log level - and
     * then performs the exact same redirect the previous
     * .failureUrl(...) call did, so behavior for the browser/user is
     * unchanged.
     *
     * Once the real cause is identified, this can be simplified back
     * down to .failureUrl(...) or left in place as permanent
     * diagnostics - it's cheap and harmless either way.
     */
    @Bean
    public AuthenticationFailureHandler oauth2LoginFailureHandler() {

        SimpleUrlAuthenticationFailureHandler handler =
                new SimpleUrlAuthenticationFailureHandler(
                        frontendBaseUrl + "/login?error"
                );

        return (request, response, exception) -> {

            String detail = exception.getMessage();

            if (exception instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException oauth2Ex
                    && oauth2Ex.getError() != null) {

                detail = "errorCode=" + oauth2Ex.getError().getErrorCode()
                        + " description=" + oauth2Ex.getError().getDescription()
                        + " uri=" + oauth2Ex.getError().getUri()
                        + " (message=" + exception.getMessage() + ")";
            }

            OAUTH2_LOGIN_LOGGER.error(
                    "OAuth2 login failed for request [{} {}]: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    detail,
                    exception
            );

            handler.onAuthenticationFailure(request, response, exception);
        };
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {

        /*
         * Local-session logout only.
         *
         * Previously this used OidcClientInitiatedLogoutSuccessHandler,
         * which round-trips the browser through Microsoft's own RP-
         * initiated logout endpoint - Microsoft's "pick an account to
         * sign out of" picker (when multiple MS accounts are active in
         * the browser) followed by its "You signed out" confirmation
         * page, before finally redirecting back here. That extra hop
         * through Microsoft's UI is the "second page" that should not
         * appear on sign-out.
         *
         * Switching to a plain local redirect: the .logout(...) block
         * below still invalidates the HttpSession, clears the
         * Authentication, and deletes the JSESSIONID cookie - that is
         * what actually signs the user out of THIS application - this
         * handler just sends the browser straight back to the login
         * screen afterwards instead of via Microsoft's pages.
         *
         * Trade-off: the browser's underlying Microsoft SSO session is
         * left alone (not ended), so this only signs the user out of
         * this app, not out of Microsoft/other apps sharing that SSO
         * session - which is why authorizationRequestResolver() above
         * forces prompt=login on the next sign-in attempt regardless.
         */
        SimpleUrlLogoutSuccessHandler handler =
                new SimpleUrlLogoutSuccessHandler();

        handler.setDefaultTargetUrl(frontendBaseUrl + "/");

        return handler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            LogoutSuccessHandler logoutSuccessHandler,
            AuthenticationFailureHandler oauth2LoginFailureHandler,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint
    ) throws Exception {

        http

                /*
                 * ---------------------------------------------------------
                 * CSRF
                 * ---------------------------------------------------------
                 *
                 * Current application uses session-based authentication
                 * with browser/API requests coming through the frontend.
                 *
                 * Keep this consistent with the existing application
                 * until CSRF is deliberately introduced end-to-end.
                 */
                .csrf(csrf -> csrf.disable())

                /*
                 * ---------------------------------------------------------
                 * AUTHORIZATION
                 * ---------------------------------------------------------
                 */
                .authorizeHttpRequests(auth -> auth

                        /*
                         * React application entry point.
                         */
                        .requestMatchers(
                                "/",
                                "/error",
                                "/favicon.ico"
                        )
                        .permitAll()

                        /*
                         * OAuth2 endpoints must be accessible so that
                         * the Microsoft login flow can start and complete.
                         *
                         * "/login" is listed explicitly alongside
                         * "/login/**" - the two don't reliably match the
                         * same requests under PathPatternRequestMatcher,
                         * and a bare GET /login must be permitted for
                         * LoginRedirectController's safety-net redirect
                         * to ever be reached instead of falling through
                         * to anyRequest().authenticated() below.
                         */
                        .requestMatchers(
                                "/login",
                                "/login/**",
                                "/oauth2/**"
                        )
                        .permitAll()

                        /*
                         * Everything else is protected.
                         */
                        .anyRequest()
                        .authenticated()
                )

                /*
                 * ---------------------------------------------------------
                 * AUTHENTICATION ENTRY POINTS
                 * ---------------------------------------------------------
                 *
                 * IMPORTANT:
                 *
                 * API requests must NOT be redirected to Microsoft.
                 * React needs a 401 so AppDataContext can switch to
                 * LoginGate.
                 */
                .exceptionHandling(exception -> exception

                        .defaultAuthenticationEntryPointFor(
                                apiAuthenticationEntryPoint,
                                PathPatternRequestMatcher.withDefaults()
                                        .matcher("/api/**")
                        )

                        /*
                         * For non-API requests, preserve normal Spring
                         * Security/OAuth2 login behaviour.
                         */
                )

                /*
                 * ---------------------------------------------------------
                 * OAUTH2 LOGIN
                 * ---------------------------------------------------------
                 */
                .oauth2Login(oauth -> oauth

                        .authorizedClientRepository(
                                authorizedClientRepository
                        )

                        .authorizedClientService(
                                authorizedClientService
                        )

                        .authorizationEndpoint(endpoint -> endpoint
                                .authorizationRequestResolver(
                                        authorizationRequestResolver
                                )
                        )

                        /*
                         * After successful Microsoft login, return to
                         * the React application.
                         */
                        .defaultSuccessUrl(
                                frontendBaseUrl + "/",
                                true
                        )

                        .failureHandler(
                                oauth2LoginFailureHandler
                        )
                )

                /*
                 * ---------------------------------------------------------
                 * OAUTH2 CLIENT
                 * ---------------------------------------------------------
                 */
                .oauth2Client(Customizer.withDefaults())

                /*
                 * ---------------------------------------------------------
                 * LOGOUT
                 * ---------------------------------------------------------
                 */
                .logout(logout -> logout

                        /*
                         * Local-only logout - see logoutSuccessHandler()
                         * javadoc above for why this no longer goes
                         * through Microsoft's own logout pages:
                         *
                         * 1. invalidate local session
                         * 2. clear Spring authentication
                         * 3. delete browser session cookie
                         * 4. redirect straight back to the React login
                         *    screen - no Microsoft round-trip
                         */
                        .logoutSuccessHandler(
                                logoutSuccessHandler
                        )

                        .invalidateHttpSession(true)

                        .clearAuthentication(true)

                        .deleteCookies("JSESSIONID")
                );

        return http.build();
    }
}