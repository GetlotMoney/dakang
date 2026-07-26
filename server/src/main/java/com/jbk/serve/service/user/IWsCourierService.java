package com.jbk.serve.service.user;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsCourierBo;
import com.jbk.tool.data.user.po.WsCourier;
import com.jbk.tool.data.user.vo.WsCourierVo;

/**
 * 配送员服务（准入状态机，REQ-079）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsCourierService extends IService<WsCourier> {

    /**
     * 分页查询（姓名/电话模糊、状态筛选，派生关联用户与服务水站名称）
     */
    PageDataVo<WsCourierVo> pageData(WsCourierBo courierBo);

    /**
     * 详情
     */
    WsCourierVo getData(Long id);

    /**
     * 按用户查询配送员准入记录（用户详情抽屉聚合展示，无则返回 null）
     */
    WsCourierVo getByUser(Long userId);

    /**
     * 人工创建配送员（REQ-079"后台至少支持人工创建/审核配送员"，创建即待审核）
     */
    Long saveData(WsCourierBo courierBo);

    /**
     * 审核/启停（状态机：1待审核→2启用/4驳回；2启用→3停用；3停用→2启用；4驳回→2启用(复审)。
     * 驳回/停用必须填写备注）
     */
    Boolean audit(WsCourierBo courierBo);
}
