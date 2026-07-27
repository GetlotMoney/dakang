package com.jbk.tool.data.product.bo;

import com.jbk.tool.data.PageBo;
import com.jbk.tool.validator.group.IdGroup;
import com.jbk.tool.validator.group.InsertGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 水种字典业务对象
 *
 * @author dakang
 * @since 2026-07-12
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(name = "WsWaterTypeBo", description = "水种字典业务对象")
public class WsWaterTypeBo extends PageBo implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @NotNull(groups = IdGroup.class, message = "水种信息不为空")
    private Long id;

    @Schema(description = "水种名称(max20)")
    @NotEmpty(groups = InsertGroup.class, message = "水种名称不为空")
    @Size(groups = InsertGroup.class, max = 20, message = "水种名称长度不能超过20")
    private String waterName;

    @Schema(description = "排序")
    @NotNull(groups = InsertGroup.class, message = "排序不为空")
    private Integer waterSort;

    @Schema(description = "是否默认水种(1)：1否 2是")
    @NotNull(groups = InsertGroup.class, message = "是否默认不为空")
    private Integer defaultFlag;

    @Schema(description = "状态(10)：1正常 2禁用")
    @NotNull(groups = InsertGroup.class, message = "状态不为空")
    private Integer waterStatus;

    @Schema(description = "水种说明(max500)")
    @Size(max = 500, message = "水种说明长度不能超过500")
    private String waterDesc;
}
