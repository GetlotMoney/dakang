package com.jbk.serve.service.station;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.station.bo.WsStationBo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.station.vo.WsStationVo;

import java.util.List;

/**
 * 水站服务
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsStationService extends IService<WsStation> {

    PageDataVo<WsStationVo> pageData(WsStationBo stationBo);

    WsStationVo getData(Long id);

    Long saveData(WsStationBo stationBo);

    Boolean updateData(WsStationBo stationBo);

    Boolean deleteData(Long id);

    /** 全部正常状态水站（下拉选择用） */
    List<WsStationVo> listData();
}
