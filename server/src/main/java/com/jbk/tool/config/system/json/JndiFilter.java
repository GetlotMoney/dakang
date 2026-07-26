package com.jbk.tool.config.system.json;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * @ClassName JndiFilter
 * @Author xs
 * @Date 2025/7/19 15:34
 * @Version 1.0
 */
@Component
public class JndiFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (request.getRequestURI().contains("${")) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "Invalid request");
            return;
        }
        filterChain.doFilter(request, response);
    }
}


