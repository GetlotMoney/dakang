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
     * 按故障码当前读字典行（LOCK IN SHARE MODE）：快照读会沿用旧 BLOCK_ORDER_FLAG；共享锁避免把同码设备的下单串行化。
     * 绝不加 LIMIT 1：FAULT_CODE 非唯一索引，同码多条的配置污染必须全部返回、由判定侧 fail-closed。
     *
     * @return 该码全部未删除的字典行；未登记返回空列表（判定侧据此 fail-closed）
     */
    @Select("SELECT * FROM ws_fault_dict WHERE FAULT_CODE = #{faultCode} AND DATA_STATUS = 0 "
            + "ORDER BY ID LOCK IN SHARE MODE")
    List<WsFaultDict> selectByCodeForShare(@Param("faultCode") String faultCode);
}
