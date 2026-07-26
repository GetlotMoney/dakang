package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.vo.MiniCatalogVo;

import java.util.List;

/**
 * 小程序目录只读读模型（E2E-03 包B：U07/U08/D02 消费的水种与水站列表）。
 *
 * @author dakang
 * @since 2026-07-24
 */
public interface IMiniCatalogService {

    /** 全量水种（含停用，enabled 标注；页面按需过滤展示）。 */
    List<MiniCatalogVo.MiniWaterTypeVo> listWaterTypes();

    /** 全量水站（含停用，status 标注；设备/出水口计数为派生列）。 */
    List<MiniCatalogVo.MiniStationVo> listStations();
}
