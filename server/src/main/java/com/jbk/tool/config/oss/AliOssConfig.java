package com.jbk.tool.config.oss;

import cn.hutool.core.util.StrUtil;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Slf4j
@Data
@Configuration
@Component
public class AliOssConfig {

    /** 凭据未配置时的占位值：保证应用可启动；真实上传前由业务层判断 isConfigured() 拦截 */
    public static final String UNCONFIGURED = "unconfigured";

    @Value("${ali.oss.url}")
    public String url;

    @Value("${ali.oss.endpoint}")
    public String endpoint;

    @Value("${ali.oss.bucketName}")
    public String bucketName;

    @Value("${ali.oss.region}")
    public String region;

    @Value("${ali.oss.ossAccessKeyId}")
    public String ossAccessKeyId;

    @Value("${ali.oss.ossAccessKeySecret}")
    public String ossAccessKeySecret;

    /** OSS 凭据是否已真实配置 */
    public boolean isConfigured() {
        return StrUtil.isNotBlank(ossAccessKeyId) && !UNCONFIGURED.equals(ossAccessKeyId);
    }

    @Bean
    public DefaultCredentialProvider defaultCredentialProvider(){
        // 六维达康项目 OSS 凭据待申请（CLAUDE.md TODO 清单）：空配置用占位凭据完成 Bean 装配，
        // 避免脚手架原版“AK 为空启动即崩”（Access key id should not be null or empty）
        if (!isConfigured()) {
            log.warn("[OSS] ali.oss.ossAccessKeyId 未配置，OSS 上传功能不可用（应用照常启动）");
            return CredentialsProviderFactory.newDefaultCredentialProvider(UNCONFIGURED, UNCONFIGURED);
        }
        return CredentialsProviderFactory.newDefaultCredentialProvider(ossAccessKeyId, ossAccessKeySecret);
    }
}


