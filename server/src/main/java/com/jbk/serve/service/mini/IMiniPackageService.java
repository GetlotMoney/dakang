package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.vo.MiniPackageVo;

import java.util.List;

/**
 * 小程序充值套餐服务（L2-READ：真实套餐只读）。
 *
 * <p>该服务只读，不涉及资金写入；下单与入账由 L2-ORDER / L2-T 负责。</p>
 */
public interface IMiniPackageService {

    /**
     * 列出在售套餐（PACKAGE_STATUS=1，逻辑删除由 @TableLogic 自动过滤）。
     *
     * @return 在售套餐列表；无在售套餐时返回空列表（不返回 null）
     */
    List<MiniPackageVo> listOnSale();
}
