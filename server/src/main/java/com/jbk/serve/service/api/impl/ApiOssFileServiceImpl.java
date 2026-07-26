package com.jbk.serve.service.api.impl;

import cn.dev33.satoken.stp.StpLogic;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.jbk.tool.config.oss.AliOssConfig;
import com.jbk.tool.data.FileInfo;
import com.jbk.tool.data.api.po.ApiOssFile;
import com.jbk.serve.mapper.api.ApiOssFileMapper;
import com.jbk.serve.service.api.IApiOssFileService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import com.jbk.tool.utils.satoken.UserTypeEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

/**
 * <p>
 * 文件 服务实现类
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Service
public class ApiOssFileServiceImpl extends ServiceImpl<ApiOssFileMapper, ApiOssFile> implements IApiOssFileService {

    @Autowired
    private AliOssConfig aliOssConfig;

    @Autowired
    private DefaultCredentialProvider credentialProvider;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo saveFile(MultipartFile file) {
        // OSS 凭据未配置时给出明确业务提示，而不是让上传在签名阶段抛底层异常
        if (!aliOssConfig.isConfigured()) {
            throw new JbkException("OSS 未配置：请在 application-*.yml 补齐 ali.oss 凭据后再使用文件上传");
        }
        // 创建OSSClient实例。
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
        OSS ossClient = OSSClientBuilder.create()
                .endpoint(aliOssConfig.getEndpoint())
                .credentialsProvider(credentialProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(aliOssConfig.getRegion())
                .build();

        // 路径拼接
        String year = DateUtils.time(DateUtils.DATE_TIME_8);
        String month = DateUtils.time(DateUtils.DATE_TIME_9);
        String day = DateUtils.time(DateUtils.DATE_TIME_10);
        // 文件名称生成
        String originalFilename = file.getOriginalFilename();
        if (originalFilename.length() > 30) {
            originalFilename = originalFilename.substring(originalFilename.length() - 30);
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String path = year + "/" + month + "/" + day + "/" + uuid + originalFilename;
        // 保存文件
        StpLogic stpLogic = null;
        try {
            stpLogic = StpKit.getStp();
            ApiOssFile apiOssFile = new ApiOssFile();
            apiOssFile.setFilePath(path);
            apiOssFile.setFileSize(file.getSize());
            apiOssFile.setFileName(originalFilename);
            apiOssFile.setUsrId(stpLogic.getLoginIdAsLong());
            UserTypeEnum userType = StpKit.getUserType(stpLogic);
            apiOssFile.setUsrType(userType.getValue());
            save(apiOssFile);
        } catch (Exception e) {
        }
        // 文件上传
        try {
            InputStream inputStream = file.getInputStream();
            // 创建PutObjectRequest对象。
            PutObjectRequest putObjectRequest = new PutObjectRequest(aliOssConfig.getBucketName(), path, inputStream);
            // 创建PutObject请求。
            PutObjectResult result = ossClient.putObject(putObjectRequest);
        } catch (Exception ce) {
            throw new JbkException("文件上传失败");
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
        FileInfo infoVo = new FileInfo(aliOssConfig.getUrl() + "/" + path, String.valueOf(file.getSize()), originalFilename);
        return infoVo;
    }
}


