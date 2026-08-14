package com.jbk.serve.service.mall;

import com.jbk.tool.data.mall.bo.MallCartSaveBo;
import com.jbk.tool.data.mall.vo.MiniMallCartVo;

/**
 * 商城购物车服务（E2E-09 S2，小程序端）。
 *
 * <p>一切读写按会话用户强制过滤，方法首参恒为 userId——不接受前端传入归属人
 * （安全铁律6：数据范围在 Service 层强制，不依赖前端传参圈定）。</p>
 *
 * @author dakang
 * @since 2026-08-08
 */
public interface IMallCartService {

    /** 本人购物车：失效行保留并标注原因，合计只算有效行。 */
    MiniMallCartVo list(Long userId);

    /** 加购或改量：increment=true 累加、false 覆盖；均由一条 upsert 原子完成。 */
    MiniMallCartVo save(Long userId, MallCartSaveBo bo);

    /** 移除一行（逻辑删除；再次加购会复活同一行）。 */
    MiniMallCartVo remove(Long userId, Long skuId);
}
