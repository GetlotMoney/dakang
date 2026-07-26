package com.jbk.tool.interceptor;

import cn.hutool.core.util.ObjectUtil;
import com.google.common.collect.Sets;
import com.jbk.tool.config.system.redis.consts.RedisKeys;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.Set;

/**
 * 使用Redis的IP频率限制过滤器，用于分布式环境
 */
@Slf4j
@Order(1)
@Component
public class RedisIpRateLimitFilter extends OncePerRequestFilter {

    private static Set<String> errorIpSet = Sets.newHashSet();
    @Resource(name = "redisTemplate1")
    private RedisTemplate redis1;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String ipAddr = IpUtils.getIpAddr(request);

        // 错误ip
        if (errorIpSet.contains(ipAddr)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Your IP has been blocked due to excessive errors.");
            return;
        }
        // 永久封禁的ip
        if (RedisUtils.sHasKey(redis1, RedisKeys.Comm.EXCEPTION_IP_ALL, ipAddr)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Your IP has been blocked due to excessive errors.");
            return;
        }
        // 临时封禁的ip
        if (RedisUtils.hasKey(redis1, RedisKeys.Comm.EXCEPTION_IP_TEMP + ipAddr)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Your IP has been blocked due to excessive errors.");
            return;
        }
        // 正常处理请求
        filterChain.doFilter(request, response);
    }

    @PostConstruct
    public void InitializationWork() {
        InputStreamReader read = null;
        BufferedReader bufferedReader = null;
        try {
            read = new InputStreamReader(
                    this.getClass().getClassLoader().getResourceAsStream("errorip.txt")
            );
            bufferedReader = new BufferedReader(read);
            for (String txt = null; (txt = bufferedReader.readLine()) != null; ) {
                if (!errorIpSet.contains(txt) && ObjectUtil.isNotEmpty(txt)) {
                    errorIpSet.add(txt);
                }
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (null != bufferedReader) {
                    bufferedReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (null != read) {
                    read.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

