package com.jbk.serve.mapper.delivery;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.tool.data.delivery.bo.AdminDeliveryAppealBo;
import com.jbk.tool.data.delivery.po.WsDeliveryAppeal;
import com.jbk.tool.data.delivery.vo.AdminDeliveryAppealItemVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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
     * 管理端申诉分页（E2E-03 包C，D-215 起按案件聚合）：一行 = 一个 TASK_ID，
     * 展示值取代表申诉（活跃申诉优先，无活跃取最近一条）并附带 appealCount /
     * activeAppealId / first-lastAppealTime，待处理案件排前。
     *
     * <p>必须在 SQL 层聚合：前端聚合会被分页边界把同任务的申诉切到两页，案件残缺。
     * 筛选口径与排序细节见 XML 注释。手机号取原值别名 *Raw，由 Service 统一脱敏。</p>
     */
    IPage<AdminDeliveryAppealItemVo> pageAdminAppeals(Page<AdminDeliveryAppealItemVo> page,
                                                      @Param("bo") AdminDeliveryAppealBo bo);

    /** 管理端申诉详情（与分页同一列集，按申诉ID单查）。 */
    AdminDeliveryAppealItemVo selectAdminAppealById(@Param("id") Long id);

    /**
     * 同一任务下的全部申诉，按申诉时间正序（D-215 申诉往来时间线）。
     * 裁决仍按单条 appealId 进行，本方法只为裁决提供历史上下文。
     */
    List<AdminDeliveryAppealItemVo> selectAdminAppealsByTaskId(@Param("taskId") Long taskId);
}
