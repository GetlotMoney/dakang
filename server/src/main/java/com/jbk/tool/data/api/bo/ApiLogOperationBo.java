package com.jbk.tool.data.api.bo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.Accessors;

import jakarta.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author xs
 * @since 2025-09-05
 */
@Getter
@Setter
@Accessors(chain = true)
@Builder
@TableName("api_log_operation")
@Schema(name = "ApiLogOperationBo", description = "$!{table.comment}")
@NoArgsConstructor
@AllArgsConstructor
public class ApiLogOperationBo extends PageBo implements Serializable {
    private static final long serialVersionUID = 1L;


    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "信息不为空")
    private Long id;

    @Schema(description = "用户姓名")
    @TableField("LOG_USER_NAME")
    private String logUserName;

    @Schema(description = "操作模块")
    @TableField("LOG_MODULE")
    private String logModule;

    /**
     * 动作类型：按落库的操作内容模糊匹配。
     *
     * <p>此前只有 {@code logModule} 一个入参，且在服务层被拆成
     * 「模块 like ? OR 内容 like ?」——筛选框写着「操作模块」，命中的却可能是内容，
     * 也无法只按动作检索。两个维度各走各的列，标签才与实际过滤一致。</p>
     */
    @Schema(description = "动作类型（操作内容）")
    @TableField("LOG_CONTENT")
    private String logContent;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeBegin;

    @Schema(description = "操作时间")
    @TableField("LOG_EXECUTE_TIME")
    private String logExecuteTimeEnd;

    @Schema(description = "是否成功1")
    @TableField("LOG_SUCCESS_FLAG")
    private Integer logSuccessFlag;

    @Schema(description = "IP")
    @TableField("LOG_IP")
    private String logIp;
}


