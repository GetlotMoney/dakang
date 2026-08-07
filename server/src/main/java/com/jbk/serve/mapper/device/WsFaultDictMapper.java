package com.jbk.serve.mapper.device;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jbk.tool.data.device.po.WsFaultDict;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 故障码字典 Mapper
 *
 * @author dakang
 * @since 2026-07-12
 */
@Mapper
public interface WsFaultDictMapper extends BaseMapper<WsFaultDict> {

    /**
     * 按故障码当前读字典行（{@code LOCK IN SHARE MODE}）。
     *
     * <p>事务权威判定不能沿用一致性快照里的旧 {@code BLOCK_ORDER_FLAG}——快照定格在事务首次
     * 普通读，之后运维把某码改成阻断，本事务仍会照旧放行。字典是只读参照数据，用共享锁而非
     * 排他锁：同一故障码可能挂在很多台设备上，排他锁会把这些设备的下单串成一条队。</p>
     *
     * <p><b>绝不加 LIMIT 1</b>：{@code FAULT_CODE} 目前只是普通索引，不是唯一索引，同码多条是可能的。
     * 取第一条会把「一条阻断、一条不阻断」这种配置污染静默压平成任选其一——运维改了半天
     * 也不知道为什么设备还在放行。全部返回，由判定侧对多行 fail-closed。</p>
     *
     * @param faultCode 设备上报的故障码
     * @return 该码全部未删除的字典行；未登记返回空列表（判定侧据此 fail-closed）
     */
    @Select("SELECT * FROM ws_fault_dict WHERE FAULT_CODE = #{faultCode} AND DATA_STATUS = 0 "
            + "ORDER BY ID LOCK IN SHARE MODE")
    List<WsFaultDict> selectByCodeForShare(@Param("faultCode") String faultCode);
}
