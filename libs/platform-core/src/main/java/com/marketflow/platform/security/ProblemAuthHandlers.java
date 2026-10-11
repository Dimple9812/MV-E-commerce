package com.marketflow.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketflow.platform.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Problem-details JSON for 401/403 raised inside the security filter chain. */
public final class ProblemAuthHandlers {

    private ProblemAuthHandlers() {
    }

    public static AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> write(response, mapper,
                GlobalExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required"),
                true);
    }

    public static AccessDeniedHandler accessDenied(ObjectMapper mapper) {
        return (request, response, ex) -> write(response, mapper,
                GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN, "forbidden",
                        "You do not have permission to perform this action"), false);
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, ProblemDetail pd,
                              boolean challenge) throws IOException {
        response.setStatus(pd.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (challenge) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        mapper.writeValue(response.getOutputStream(), pd);
    }
}
