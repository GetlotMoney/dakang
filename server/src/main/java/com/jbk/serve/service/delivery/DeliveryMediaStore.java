package com.jbk.serve.service.delivery;

/**
 * 配送媒体物理存储适配器（E2E-03 A5：一期只有受控本地实现，不接对象存储）。
 * 数据库行（ws_delivery_media）是媒体存在与归属的唯一权威；本接口只负责字节落地。
 *
 * @author dakang
 * @since 2026-07-23
 */
public interface DeliveryMediaStore {

    /** 按媒体键落盘内容；失败必须抛出（登记事务随之回滚，不允许「有行无文件」静默成立）。 */
    void store(String mediaKey, byte[] content);
}
