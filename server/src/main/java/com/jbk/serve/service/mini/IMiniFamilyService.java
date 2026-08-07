package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniAddressSaveBo;
import com.jbk.tool.data.mini.bo.MiniFamilySaveBo;
import com.jbk.tool.data.mini.vo.MiniAddressVo;
import com.jbk.tool.data.mini.vo.MiniFamilyProfileVo;
import com.jbk.tool.data.user.po.WsUserAddress;

import java.util.List;

/**
 * 家庭资料与配送地址（2026-08-02 链路落地）。
 * <p>作用范围恒为当前会话用户（铁律6：不收前端 userId）；地址电话对外恒 PhoneMask 脱敏，
 * 原号仅供配送创单按 addressId 服务端解引用写任务快照。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
public interface IMiniFamilyService {

    // ---------- 地址簿 ----------

    List<MiniAddressVo> listAddresses(Long userId);

    MiniAddressVo getAddress(Long userId, Long addressId);

    MiniAddressVo saveAddress(Long userId, MiniAddressSaveBo bo);

    void deleteAddress(Long userId, Long addressId);

    /**
     * 配送创单专用：按归属解引用地址行（含原号）。
     * <p>越权/不存在/已删除一律 fail-closed 抛出；调用方只把号码写入任务快照，不回流前端。</p>
     */
    WsUserAddress requireOwnAddress(Long userId, Long addressId);

    // ---------- 家庭资料 ----------

    MiniFamilyProfileVo getFamilyProfile(Long userId);

    MiniFamilyProfileVo saveFamilyProfile(Long userId, MiniFamilySaveBo bo);

    void deleteFamilyProfile(Long userId);
}
