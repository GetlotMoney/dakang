package com.jbk.tool.config.system;

import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

@WebFilter(urlPatterns = "/**")
@Component
public class RequestWrapperJsonFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        //请求参数为JSON类型，则使用自定义包装
        if (request instanceof HttpServletRequest
                && "application/json".equals(((HttpServletRequest) request).getHeader("Content-Type"))) {
            chain.doFilter(new RequestWrapper((HttpServletRequest) request), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
    }
}
