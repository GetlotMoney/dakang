package com.jbk.serve.service.api;

import com.jbk.tool.data.FileInfo;
import com.jbk.tool.data.api.po.ApiOssFile;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

/**
 * <p>
 * 文件 服务类
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
public interface IApiOssFileService extends IService<ApiOssFile> {

    FileInfo saveFile(MultipartFile file);
}


