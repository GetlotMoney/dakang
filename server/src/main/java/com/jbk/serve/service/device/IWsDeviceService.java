package com.jbk.serve.service.device;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.device.bo.WsDeviceBo;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.vo.WsDeviceTelemetryVo;
import com.jbk.tool.data.device.vo.WsDeviceVo;
import com.jbk.tool.data.device.vo.WsQrcodeVo;

import java.util.List;

/**
 * 设备服务（设备中控：档案 CRUD + 详情页只读数据）
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsDeviceService extends IService<WsDevice> {

    PageDataVo<WsDeviceVo> pageData(WsDeviceBo deviceBo);

    WsDeviceVo getData(Long id);

    Long saveData(WsDeviceBo deviceBo);

    Boolean updateData(WsDeviceBo deviceBo);

    Boolean deleteData(Long id);

    /** 设备绑定的二维码列表（一期只读，REQ-064 完整管理为商业一期） */
    List<WsQrcodeVo> qrcodeList(Long deviceId);

    /** 最近一条遥测（详情页 TDS/滤芯/信号展示，REQ-038/039 骨架） */
    WsDeviceTelemetryVo latestTelemetry(Long deviceId);
}
