package com.jbk.serve.service.device;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.device.bo.WsDeviceOutletBo;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.vo.WsDeviceOutletVo;

import java.util.List;

/**
 * 设备出水口服务
 *
 * @author dakang
 * @since 2026-07-12
 */
public interface IWsDeviceOutletService extends IService<WsDeviceOutlet> {

    /** 按设备查询出水口列表（设备详情页） */
    List<WsDeviceOutletVo> listByDevice(Long deviceId);

    Long saveData(WsDeviceOutletBo outletBo);

    Boolean updateData(WsDeviceOutletBo outletBo);

    Boolean deleteData(Long id);
}
