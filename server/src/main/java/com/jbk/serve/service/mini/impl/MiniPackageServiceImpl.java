package com.jbk.serve.service.mini.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.product.WsPackageMapper;
import com.jbk.serve.service.mini.IMiniPackageService;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.tool.data.mini.vo.MiniPackageVo;
import com.jbk.tool.data.product.po.WsPackage;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 小程序充值套餐服务实现（L2-READ 只读）。
 *
 * <p>只返回在售套餐；SCOPE_JSON 不下发（范围校验属服务端职责，见 {@link MiniPackageVo} 注释）。</p>
 */
@Service
@RequiredArgsConstructor
public class MiniPackageServiceImpl implements IMiniPackageService {

    /** 套餐状态(1330)：1 在售。 */
    private static final int PACKAGE_STATUS_ON_SALE = 1;

    private final WsPackageMapper wsPackageMapper;

    @Override
    public List<MiniPackageVo> listOnSale() {
        // DATA_STATUS 由 BaseEntity 的 @TableLogic 自动附加，无需显式过滤。
        List<WsPackage> rows = wsPackageMapper.selectList(Wrappers.lambdaQuery(WsPackage.class)
                .eq(WsPackage::getPackageStatus, PACKAGE_STATUS_ON_SALE)
                .orderByAsc(WsPackage::getPayAmount)
                .orderByAsc(WsPackage::getId));
        return rows.stream().map(MiniPackageServiceImpl::toVo).toList();
    }

    private static MiniPackageVo toVo(WsPackage po) {
        MiniPackageVo vo = new MiniPackageVo();
        vo.setPackageId(po.getId());
        vo.setPackageName(po.getPackageName());
        vo.setPayAmountFen(po.getPayAmount());
        vo.setWaterMl(po.getWaterMl());
        vo.setBonusAmountFen(po.getBonusAmount());
        vo.setUnitPriceSnap(po.getUnitPriceSnap());
        vo.setExpireDays(po.getExpireDays());
        vo.setPurchasable(isPurchasable(po));
        vo.setPackageRemark(po.getPackageRemark());
        return vo;
    }

    /** 范围原文不下发；只向页面暴露服务端严格规范化后的可购结论。 */
    private static boolean isPurchasable(WsPackage po) {
        try {
            WaterCardScope.normalize(po.getScopeJson(), "套餐");
            return true;
        } catch (JbkException invalidScope) {
            return false;
        }
    }
}
