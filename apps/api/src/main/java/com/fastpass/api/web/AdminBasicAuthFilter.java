package com.fastpass.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class AdminBasicAuthFilter extends OncePerRequestFilter {

    private static final String BASIC_PREFIX = "Basic ";
    private static final String REALM = "FastPass Admin";

    private final String adminUsername;
    private final String adminPassword;

    public AdminBasicAuthFilter(
            @Value("${fastpass.admin.username:admin}") String adminUsername,
            @Value("${fastpass.admin.password:admin1234}") String adminPassword
    ) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();

        if (!uri.startsWith("/admin")) {
            return true;
        }

        return uri.equals("/admin/styles.css")
                || uri.equals("/admin/app.js")
                || uri.equals("/admin/favicon.ico");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader("Authorization");

        if (isAuthenticated(authorizationHeader)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(
                "WWW-Authenticate",
                "Basic realm=\"" + REALM + "\", charset=\"UTF-8\""
        );
    }

    private boolean isAuthenticated(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BASIC_PREFIX)) {
            return false;
        }

        String encodedCredentials = authorizationHeader.substring(BASIC_PREFIX.length());

        String decodedCredentials;
        try {
            decodedCredentials = new String(
                    Base64.getDecoder().decode(encodedCredentials),
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException e) {
            return false;
        }

        String expectedCredentials = adminUsername + ":" + adminPassword;

        return expectedCredentials.equals(decodedCredentials);
    }
}