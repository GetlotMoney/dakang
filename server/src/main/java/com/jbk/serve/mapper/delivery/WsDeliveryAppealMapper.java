package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 配送申诉 Mapper（E2E-03 包A）。裁决/举证的并发唯一由
 * uk_appeal_active_task（生成列唯一键）与条件 UPDATE 保证。
 *
 * @author dakang
 * @since 2026-07-23
 */
@Mapper
public interface WsDeliveryAppealMapper extends BaseMapper<WsDeliveryAppeal> {

    /**
     * 管理端申诉分页（E2E-03 包C）：申诉 join 任务/订单/用户/处理人派生展示列，
     * 待处理排前。手机号取原值别名 *Raw，由 Service 层统一 PhoneMask 脱敏。
     */
    IPage<AdminDeliveryAppealItemVo> pageAdminAppeals(Page<AdminDeliveryAppealItemVo> page,
                                                      @Param("bo") AdminDeliveryAppealBo bo);

    /** 管理端申诉详情（与分页同一列集，按申诉ID单查）。 */
    AdminDeliveryAppealItemVo selectAdminAppealById(@Param("id") Long id);
}
