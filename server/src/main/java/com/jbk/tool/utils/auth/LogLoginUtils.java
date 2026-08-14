package com.jbk.tool.utils.auth;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.jbk.tool.consts.ApiEnum;
import com.jbk.tool.data.api.po.ApiLogLogin;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.IpUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

public class LogLoginUtils {
    public static String unknown = "Unknown";

    public static ApiLogLogin createLogLogin(
            ApiEnum.LoginType loginType,
            Long logUserId,
            String logUserType,
            String logUserName
    ) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();

        ApiLogLogin logLogin = new ApiLogLogin();
        logLogin.setLogUserId(logUserId);
        logLogin.setLogUserType(logUserType);
        logLogin.setLogUserName(logUserName);
        logLogin.setLogExecuteTime(DateUtils.time());
        logLogin.setLogType(loginType.value());
        logLogin.setLogIp(IpUtils.getIpAddr(request));
        logLogin.setLogUserAgent(request.getHeader("User-Agent"));
        // ua===============================
        UserAgent ua = UserAgentUtil.parse(request.getHeader("User-Agent"));
        logLogin.setUaIsMobile(ua.isMobile() ? ApiEnum.Flag.YES.value() : ApiEnum.Flag.NO.value());
        logLogin.setUaPlatform(ua.getPlatform().toString());
        logLogin.setUaBrowser(ua.getBrowser().toString());
        logLogin.setUaBrowserVersion(StrUtil.isEmpty(ua.getVersion()) ? unknown : ua.getVersion());
        logLogin.setUaEngine(ua.getEngine().toString());
        logLogin.setUaEngineVersion(StrUtil.isEmpty(ua.getEngineVersion()) ? unknown : ua.getEngineVersion());
        logLogin.setUaOs(ua.getOs().toString());
        logLogin.setUaOsVersion(StrUtil.isEmpty(ua.getOsVersion()) ? unknown : ua.getVersion());
        return logLogin;
    }
}


