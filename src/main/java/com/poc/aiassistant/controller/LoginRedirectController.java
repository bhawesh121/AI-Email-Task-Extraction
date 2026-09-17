package com.poc.aiassistant.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Defense-in-depth only.
 *
 * The real fix for the "/login?error" 404 (Whitelabel Error Page) bug is
 * in the reverse proxy: nginx.conf must proxy ONLY
 * "/sla/login/oauth2/code/**" to this backend, and let a bare
 * "/sla/login" (or "/sla/login?error") fall through to the React SPA,
 * which is what actually renders the login screen (see LoginGate in
 * App.jsx).
 *
 * This controller exists purely so that IF that proxy config is ever
 * wrong, reverted, or bypassed (new environment, someone edits
 * nginx.conf later without reading this comment, etc.), a bare GET
 * /login reaching this backend still results in a normal redirect back
 * to the frontend instead of Spring Boot's raw Whitelabel 404 page. It
 * should not be relied on as the primary fix - fix nginx.conf.
 */
@Controller
public class LoginRedirectController {

    private final String frontendBaseUrl;

    public LoginRedirectController(
            @Value("${app.frontend-base-url:http://localhost:5173}")
            String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @GetMapping("/login")
    public String redirectBareLoginToFrontend(
            @RequestParam(name = "error", required = false) String error) {

        String target = frontendBaseUrl + "/";

        if (error != null) {
            target = frontendBaseUrl + "/login?error";
        }

        return "redirect:" + target;
    }
}