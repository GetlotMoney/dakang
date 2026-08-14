package com.jbk.tool.data.mall.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 商城购物车行 Po（E2E-09 S2）：一人一 SKU 恒一行。
 *
 * <p>移除走逻辑删除，再次加购必须复活同一行（唯一键 uk(USER_ID,SKU_ID) 不含
 * DATA_STATUS）——服务层用 upsert 一步完成建行/复活/改量。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_mall_cart_item")
public class WsMallCartItem extends BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键")
    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    @Schema(description = "归属用户ID：读写恒以会话人过滤")
    @TableField("USER_ID")
    private Long userId;

    @Schema(description = "SKU ID")
    @TableField("SKU_ID")
    private Long skuId;

    @Schema(description = "数量(件)：恒>0")
    @TableField("QUANTITY")
    private Integer quantity;
}
