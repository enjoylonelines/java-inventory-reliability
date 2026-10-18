package com.inventory.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import org.springframework.web.servlet.HandlerInterceptor;

/** Static tokens are suitable only for an isolated development environment. */
final class OracleTokenGuard implements HandlerInterceptor {
    private final byte[] viewer;
    private final byte[] operator;

    OracleTokenGuard(String viewer, String operator) {
        if (!acceptable(viewer) || !acceptable(operator) || viewer.equals(operator)) {
            throw new IllegalArgumentException("invalid inventory API credential configuration");
        }
        this.viewer = viewer.getBytes(StandardCharsets.UTF_8);
        this.operator = operator.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        Enumeration<String> headers = request.getHeaders("Authorization");
        if (headers == null || !headers.hasMoreElements()) return deny(response, 401, "UNAUTHORIZED");
        String authorization = headers.nextElement();
        if (headers.hasMoreElements() || authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() > 519) return deny(response, 401, "UNAUTHORIZED");
        byte[] candidate = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        boolean isViewer = MessageDigest.isEqual(viewer, candidate);
        boolean isOperator = MessageDigest.isEqual(operator, candidate);
        if (!isViewer && !isOperator) return deny(response, 401, "UNAUTHORIZED");
        if (!"GET".equals(request.getMethod()) && !isOperator) return deny(response, 403, "FORBIDDEN");
        return true;
    }

    private static boolean deny(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
        return false;
    }

    /** A basic misconfiguration check, not proof of token entropy. */
    private static boolean acceptable(String token) {
        if (token == null || token.length() < 32 || token.length() > 512) return false;
        boolean[] distinct = new boolean[127];
        int unique = 0;
        int classes = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 33 || c > 126) return false;
            if (!distinct[c]) { distinct[c] = true; unique++; }
            if (c >= 'a' && c <= 'z') classes |= 1;
            else if (c >= 'A' && c <= 'Z') classes |= 2;
            else if (c >= '0' && c <= '9') classes |= 4;
            else classes |= 8;
        }
        if (unique < 16 || Integer.bitCount(classes) < 3) return false;
        for (int period = 1; period <= token.length() / 2; period++) {
            if (token.length() % period != 0) continue;
            boolean repeats = true;
            for (int index = period; index < token.length(); index++) {
                if (token.charAt(index) != token.charAt(index % period)) { repeats = false; break; }
            }
            if (repeats) return false;
        }
        return true;
    }
}
