package com.jbk.tool.data.api.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;

import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * <p>
 * 文件
 * </p>
 *
 * @author xs
 * @since 2025-09-04
 */
@Getter
@Setter
@Accessors(chain = true)
@TableName("api_oss_file")
@Schema(name = "ApiOssFile", description = "$!{table.comment}")
public class ApiOssFile extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "文件地址(mx150)")
    @TableField("FILE_PATH")
    private String filePath;

    @Schema(description = "文件大小")
    @TableField("FILE_SIZE")
    private Long fileSize;

    @Schema(description = "文件名称(max50)")
    @TableField("FILE_NAME")
    private String fileName;

    @Schema(description = "所属用户ID")
    @TableField("USR_ID")
    private Long usrId;

    @Schema(description = "用户类型max20")
    @TableField("USR_TYPE")
    private Integer usrType;
}


