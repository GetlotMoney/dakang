package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.device.WsDeviceMapper;
import com.jbk.serve.mapper.device.WsDeviceOutletMapper;
import com.jbk.serve.mapper.device.WsQrcodeMapper;
import com.jbk.serve.mapper.station.WsStationMapper;
import com.jbk.serve.mapper.trade.WsOrderMapper;
import com.jbk.serve.mapper.user.WsCardMapper;
import com.jbk.serve.mapper.user.WsCardMemberMapper;
import com.jbk.serve.service.device.DeviceAvailability;
import com.jbk.serve.service.device.DeviceAvailabilityGuard;
import com.jbk.serve.service.trade.WaterBillingMath;
import com.jbk.serve.service.mini.IMiniDeviceService;
import com.jbk.serve.service.mini.card.CardMemberRule;
import com.jbk.serve.service.mini.card.WaterCardScope;
import com.jbk.serve.service.ops.IWsDomainEventService;
import com.jbk.serve.service.trade.MemberDayLimitMath;
import com.jbk.tool.config.system.redis.utils.RedisUtils;
import com.jbk.tool.consts.mini.MiniRejectCode;
import com.jbk.tool.consts.ops.OpsEnum;
import com.jbk.tool.data.device.po.WsDevice;
import com.jbk.tool.data.device.po.WsDeviceOutlet;
import com.jbk.tool.data.device.po.WsQrcode;
import com.jbk.tool.data.mini.vo.OutletSummaryVo;
import com.jbk.tool.data.mini.vo.ScanSessionInfo;
import com.jbk.tool.data.mini.vo.ScanSessionVo;
import com.jbk.tool.data.mini.vo.WaterDeviceContextVo;
import com.jbk.tool.data.mini.vo.WaterEligibilityVo;
import com.jbk.tool.data.station.po.WsStation;
import com.jbk.tool.data.user.po.WsCard;
import com.jbk.tool.data.user.po.WsCardMember;
import com.jbk.tool.exception.JbkException;
import com.jbk.tool.utils.DateUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 小程序扫码取水服务实现（L1a）。
 * <p>
 * 扫码会话存 Redis（键 {@code mini:scan:{id}}，TTL 300s，一次性由下单链路消费）。
 * 登录人一律由会话取；context/eligibility 校验会话归属登录人，防会话串用（铁律6）。
 * 拒绝码见 {@link MiniRejectCode}；设备可用性/卡阻断作为 eligibility 数据字段返回而非异常。
 * </p>
 *
 * @author dakang
 * @since 2026-07-19
 */
@Slf4j
@Service
public class MiniDeviceServiceImpl implements IMiniDeviceService {

    /** 扫码会话 Redis 前缀 */
    private static final String SCAN_KEY_PREFIX = "mini:scan:";
    /** 扫码会话有效期（秒），与 L0 契约一致 */
    private static final int SCAN_TTL_SECONDS = 300;
    /** 二维码类型：3=万能码 */
    private static final int QRCODE_TYPE_UNIVERSAL = 3;

    @Autowired
    private WsQrcodeMapper qrcodeMapper;
    @Autowired
    private WsDeviceMapper deviceMapper;
    @Autowired
    private WsDeviceOutletMapper outletMapper;
    @Autowired
    private WsStationMapper stationMapper;
    @Autowired
    private WsCardMapper cardMapper;
    @Autowired
    private WsCardMemberMapper cardMemberMapper;
    @Autowired
    private WsOrderMapper wsOrderMapper;
    /** 设备可用性唯一加载器：预检与下单事务、指令准备共用同一套字典读取与冲突处理。 */
    @Autowired
    private DeviceAvailabilityGuard availabilityGuard;
    @Autowired
    private IWsDomainEventService domainEventService;

    @Resource(name = "redisTemplate1")
    private RedisTemplate<String, Object> redis;

    @Override
    public ScanSessionVo resolveScan(String rawCode, Long userId) {
        if (StrUtil.isBlank(rawCode)) {
            throw new JbkException("扫码内容不能为空", MiniRejectCode.INVALID_QR_CODE);
        }
        WsQrcode qrcode = qrcodeMapper.selectOne(
                Wrappers.lambdaQuery(WsQrcode.class).eq(WsQrcode::getQrcodeContent, rawCode.trim()));
        if (ObjectUtil.isNull(qrcode)) {
            throw new JbkException("二维码无效，请扫描设备出水口上的取水码", MiniRejectCode.INVALID_QR_CODE);
        }
        if (ObjectUtil.equal(qrcode.getQrcodeStatus(), 2)) {
            throw new JbkException("二维码已过期或已停用，请按设备屏幕提示重新获取", MiniRejectCode.QR_EXPIRED);
        }
        if (ObjectUtil.equal(qrcode.getQrcodeType(), QRCODE_TYPE_UNIVERSAL)) {
            throw new JbkException("万能码需现场选择设备与出水口，该流程待后续定型", MiniRejectCode.UNIVERSAL_CODE_PENDING);
        }
        // 新版/旧版码必须已绑定设备与出水口；未绑定视同不可用（避免扫码后无法定位取水口）
        if (ObjectUtil.isNull(qrcode.getDeviceId()) || ObjectUtil.isNull(qrcode.getOutletId())) {
            throw new JbkException("该二维码尚未绑定出水口，暂不可用", MiniRejectCode.QR_EXPIRED);
        }
        WsDeviceOutlet outlet = outletMapper.selectById(qrcode.getOutletId());
        WsDevice device = deviceMapper.selectById(qrcode.getDeviceId());
        if (ObjectUtil.isNull(outlet) || ObjectUtil.isNull(device)) {
            // 种子/档案完整性问题：码存在但关联档案缺失，记审计便于运维排查
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, rawCode, null,
                    "扫码解析失败：二维码关联设备/出水口档案缺失（deviceId=" + qrcode.getDeviceId()
                            + " outletId=" + qrcode.getOutletId() + "）");
            throw new JbkException("二维码无效，请扫描设备出水口上的取水码", MiniRejectCode.INVALID_QR_CODE);
        }
        // CARD-SCOPE 共键校验：qrcode/station/device/outlet 任一错位即 fail-closed 不铸会话。
        // 没有这道闸，一张贴错/被改绑的码就能让用户对着 A 设备扫码、却向 B 设备出水口下单扣款。
        WsStation station = stationMapper.selectById(device.getStationId());
        if (ObjectUtil.isNull(station)) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, rawCode, null,
                    "扫码解析失败：设备所属水站档案缺失（deviceId=" + device.getId()
                            + " stationId=" + device.getStationId() + "）");
            throw new JbkException("二维码无效，请扫描设备出水口上的取水码", MiniRejectCode.INVALID_QR_CODE);
        }
        if (ObjectUtil.notEqual(outlet.getDeviceId(), device.getId())) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, rawCode, null,
                    "扫码解析失败：二维码档案共键错位（qrcodeId=" + qrcode.getId()
                            + " outlet.deviceId=" + outlet.getDeviceId() + " deviceId=" + device.getId() + "）");
            throw new JbkException("二维码无效，请扫描设备出水口上的取水码", MiniRejectCode.INVALID_QR_CODE);
        }
        // 档案级停用（1正常 2禁用）：禁用的水站/出水口不得铸会话；设备无档案停用列，
        // 其在线/运行可用性属瞬时状态，仍由 eligibility 判定并在下单前复验。
        if (ObjectUtil.notEqual(station.getStationStatus(), 1)) {
            throw new JbkException("该水站已停用，暂不可取水", MiniRejectCode.QR_EXPIRED);
        }
        if (ObjectUtil.notEqual(outlet.getOutletStatus(), 1)) {
            throw new JbkException("该出水口已停用，暂不可取水", MiniRejectCode.QR_EXPIRED);
        }

        // S2 报价冻结：水种与单价在扫码这一刻定死，此后确认页展示、下单装配、事务终判全吃这一份。
        // 缺一不可——只冻水种不冻价，用户确认期间的调价照样会静默改扣款额。
        Long frozenWaterTypeId = outlet.getWaterTypeId();
        if (ObjectUtil.isNull(frozenWaterTypeId)) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, rawCode, null,
                    "扫码解析失败：出水口未配置水种（outletId=" + outlet.getId() + "）");
            throw new JbkException("出水口水种配置异常，请联系管理员", MiniRejectCode.QR_EXPIRED);
        }
        int frozenUnitPrice = requireOutletPrice(outlet);

        String scanSessionId = IdUtil.fastSimpleUUID();
        String quotedAt = DateUtils.time();
        // qrcodeId/stationId 一并铸入会话：下单事务凭它们在锁内重核共键（预检不是安全边界）。
        JSONObject sessionValue = JSONUtil.createObj()
                .set("userId", userId)
                .set("qrcodeId", qrcode.getId())
                .set("stationId", station.getId())
                .set("deviceId", device.getId())
                .set("deviceNo", device.getDeviceNo())
                .set("outletId", outlet.getId())
                .set("waterTypeId", frozenWaterTypeId)
                .set("unitPriceFenPerLiter", frozenUnitPrice)
                .set("quotedAt", quotedAt);
        RedisUtils.set(redis, SCAN_KEY_PREFIX + scanSessionId, sessionValue.toString(), SCAN_TTL_SECONDS);

        return new ScanSessionVo()
                .setScanSessionId(scanSessionId)
                .setDeviceNo(device.getDeviceNo())
                .setOutletId(outlet.getId())
                .setExpiresAt(DateUtils.timeTransition(DateUtils.addDateSeconds(new Date(), SCAN_TTL_SECONDS)));
    }

    @Override
    public WaterDeviceContextVo getWaterContext(String scanSessionId, Long userId) {
        JSONObject session = loadSession(scanSessionId, userId);
        ScanSessionInfo frozen = toSessionInfo(scanSessionId, session);
        WsDeviceOutlet outlet = requireOutlet(session.getLong("outletId"));
        WsDevice device = requireDevice(session.getLong("deviceId"));
        WsStation station = stationMapper.selectById(device.getStationId());
        // 报价漂移即会话作废：绝不返回「扫码时水种 + 当前价格」这类混合体，
        // 那会让用户在一个自身矛盾的页面上确认。
        requireQuoteUnchanged(frozen, outlet);

        return new WaterDeviceContextVo()
                .setScanSessionId(scanSessionId)
                .setStationId(device.getStationId())
                .setStationName(ObjectUtil.isNull(station) ? null : station.getStationName())
                .setDeviceNo(device.getDeviceNo())
                .setDeviceName(device.getDeviceName())
                .setOnlineStatus(mapOnlineStatus(device.getOnlineStatus()))
                .setRunStatus(mapRunStatus(device.getRunStatus()))
                .setOutlet(buildOutletSummary(outlet, frozen))
                .setQuotedAt(frozen.getQuotedAt())
                .setExpiresAt(remainExpiresAt(scanSessionId));
    }

    @Override
    public WaterEligibilityVo checkEligibility(String scanSessionId, Long cardId, Long userId) {
        JSONObject session = loadSession(scanSessionId, userId);
        WsDeviceOutlet outlet = requireOutlet(session.getLong("outletId"));
        WsDevice device = requireDevice(session.getLong("deviceId"));

        WaterEligibilityVo vo = new WaterEligibilityVo();
        fillDeviceAvailability(vo, device, outlet);
        fillCardBlock(vo, cardId, userId, device.getStationId(), device.getId(), outlet.getId());
        return vo;
    }

    @Override
    public ScanSessionInfo loadScanSession(String scanSessionId, Long userId) {
        JSONObject session = loadSession(scanSessionId, userId);
        return toSessionInfo(scanSessionId, session);
    }

    /**
     * 会话 → 内部数据，冻结报价字段严格校验。
     *
     * <p>S2 之前铸造的会话没有 waterTypeId/unitPriceFenPerLiter/quotedAt。<b>绝不回退去读当前价</b>——
     * 那正是本包要消灭的行为（页面看到的是扫码时的价，扣款用的是提交时的价）。
     * 缺字段一律按会话失效处理，代价是升级瞬间的在途会话要重扫一次，换来的是没有一笔按旧会话
     * 以新价扣款。</p>
     */
    private ScanSessionInfo toSessionInfo(String scanSessionId, JSONObject session) {
        Long waterTypeId = session.getLong("waterTypeId");
        Integer unitPrice = session.getInt("unitPriceFenPerLiter");
        String quotedAt = session.getStr("quotedAt");
        if (ObjectUtil.isNull(waterTypeId) || ObjectUtil.isNull(unitPrice) || StrUtil.isBlank(quotedAt)) {
            throw new JbkException("扫码会话已过期，请重新扫码", MiniRejectCode.SCAN_SESSION_EXPIRED);
        }
        return new ScanSessionInfo()
                .setScanSessionId(scanSessionId)
                .setUserId(session.getLong("userId"))
                .setQrcodeId(session.getLong("qrcodeId"))
                .setStationId(session.getLong("stationId"))
                .setDeviceId(session.getLong("deviceId"))
                .setDeviceNo(session.getStr("deviceNo"))
                .setOutletId(session.getLong("outletId"))
                .setWaterTypeId(waterTypeId)
                .setUnitPriceFenPerLiter(WaterBillingMath.requireUnitPrice(unitPrice))
                .setQuotedAt(quotedAt);
    }

    @Override
    public void consumeScanSession(String scanSessionId) {
        RedisUtils.del(redis, SCAN_KEY_PREFIX + scanSessionId);
    }

    // ==================== 设备可用性（DeviceAvailability 封闭枚举） ====================

    private void fillDeviceAvailability(WaterEligibilityVo vo, WsDevice device, WsDeviceOutlet outlet) {
        // 判定唯一出处是 DeviceAvailability（E2E-05 包B 抽出）：本方法只负责查字典行并搬运结论。
        // 在这里重写任何一条状态分支都是第二份真相，评审一票否决。
        // 字典读取与 0/1/多行处理统一走 Guard：此前这里用单值 selectOne，同一故障码被误录两条时
        // MyBatis-Plus 直接抛内部异常，重复配置既拿不到 FAULT_DICT_CONFLICT 诊断也不留痕。
        DeviceAvailability.Verdict verdict = availabilityGuard.judge(device, outlet).verdict();
        vo.setAvailability(verdict.code()).setReason(verdict.reason());
    }

    // ==================== 卡阻断（CardBlockCode） ====================

    /**
     * CARD-SCOPE：只预检调用方指定的那张卡——后端不再自行挑卡，否则会出现「预检卡A、下单卡B」，
     * 预检结论对下单毫无约束。预检不是安全边界，下单事务内会以同一套 {@link WaterCardScope}
     * 口径二次校验；此处只负责把可预见的阻断提前、友好地暴露给用户。
     *
     * <p>CARD-MEMBER：卡可及性从「仅卡主」扩为「卡主或有效成员」。他人无关卡、
     * 已撤销/未生效/已失效的成员授权与不存在卡仍统一 CARD_NOT_ACCESSIBLE 同码同文案——
     * 拒因一旦可区分，就成了探测他人卡与授权历史的信道。有效判定与下单事务同口径
     * （{@link CardMemberRule}），成员剩余限额只在此做只读展示估算。</p>
     */
    private void fillCardBlock(WaterEligibilityVo vo, Long cardId, Long userId,
                               Long stationId, Long deviceId, Long outletId) {
        if (ObjectUtil.isNull(cardId)) {
            vo.setCardBlock(cardBlock("CARD_MISSING", "未查询到可用水卡，请先开卡或充值"));
            return;
        }
        // @TableLogic 自动过滤逻辑删除卡：删除卡与不存在卡同路径，天然不泄露存在性。
        WsCard card = cardMapper.selectById(cardId);
        if (ObjectUtil.isNull(card)) {
            vo.setCardBlock(cardBlock("CARD_NOT_ACCESSIBLE", "水卡不存在或无权使用"));
            return;
        }
        Long memberDayLimitMl = null;
        boolean asMember = ObjectUtil.notEqual(card.getUserId(), userId);
        if (asMember) {
            // 非卡主：必须持有当前有效的成员授权；无效授权与不存在卡同码同文案（不泄露授权历史）。
            WsCardMember grant = cardMemberMapper.selectByCardAndUserIncludingDeleted(cardId, userId);
            if (!CardMemberRule.isActive(grant, DateUtils.time())) {
                vo.setCardBlock(cardBlock("CARD_NOT_ACCESSIBLE", "水卡不存在或无权使用"));
                return;
            }
            memberDayLimitMl = grant.getDayLimitMl();
        }
        Integer status = card.getCardStatus();
        boolean expiredByTime = StrUtil.isNotBlank(card.getExpireTime())
                && card.getExpireTime().compareTo(DateUtils.time()) < 0;
        if (ObjectUtil.equal(status, 2)) {
            vo.setCardBlock(cardBlock("CARD_FROZEN", "水卡已冻结，暂不可取水"));
            return;
        }
        if (ObjectUtil.equal(status, 4)) {
            vo.setCardBlock(cardBlock("CARD_CANCELLED", "水卡已注销"));
            return;
        }
        if (ObjectUtil.equal(status, 3) || expiredByTime) {
            vo.setCardBlock(cardBlock("CARD_EXPIRED", "水卡已过期"));
            return;
        }
        // 范围判定与下单事务同一解析器：空/非法 JSON=SCOPE_INVALID（默认拒绝），
        // 合法但未命中本站-设备-出水口=SCOPE_DENIED。
        WaterCardScope scope;
        try {
            scope = WaterCardScope.normalize(card.getScopeJson(), "水卡");
        } catch (Exception invalid) {
            vo.setCardBlock(cardBlock("CARD_SCOPE_INVALID", "水卡可用范围未配置或非法（默认拒绝）"));
            return;
        }
        if (!scope.allows(stationId, deviceId, outletId)) {
            vo.setCardBlock(cardBlock("CARD_SCOPE_DENIED", "该水卡不适用于当前设备，请更换设备或水卡"));
            return;
        }
        // 卡可用：maxAllowedMl 仅是水量支付参考上限（=剩余水量），余额支付不受它阻断。
        vo.setMaxAllowedMl(card.getBalanceMl());
        // CARD-MEMBER：仅「成员且配置了 DAY_LIMIT_ML」返回当日剩余额度（只读展示估算）；
        // 卡主与不限额成员保持 null——该值不构成放行依据，最终判定在取水事务锁内完成。
        if (asMember && ObjectUtil.isNotNull(memberDayLimitMl)) {
            vo.setRemainingDailyLimitMl(estimateMemberRemaining(cardId, userId, memberDayLimitMl));
        }
    }

    /** 成员当日剩余限额估算：限额-当天占用（口径同 MemberDayLimitMath），溢出按 0 剩余（fail-closed）。 */
    private Long estimateMemberRemaining(Long cardId, Long userId, Long dayLimitMl) {
        String now = DateUtils.time();
        try {
            long occupied = MemberDayLimitMath.occupiedMl(wsOrderMapper.selectMemberDayWaterOrders(
                    cardId, userId, MemberDayLimitMath.dayStart(now), MemberDayLimitMath.dayEnd(now)));
            return MemberDayLimitMath.remaining(dayLimitMl, occupied);
        } catch (ArithmeticException overflow) {
            return 0L;
        }
    }

    private WaterEligibilityVo.CardBlockVo cardBlock(String code, String message) {
        return new WaterEligibilityVo.CardBlockVo().setCode(code).setMessage(message);
    }

    // ==================== 会话与档案读取 ====================

    private JSONObject loadSession(String scanSessionId, Long userId) {
        if (StrUtil.isBlank(scanSessionId)) {
            throw new JbkException("扫码会话已过期，请重新扫码", MiniRejectCode.SCAN_SESSION_EXPIRED);
        }
        Object raw = RedisUtils.get(redis, SCAN_KEY_PREFIX + scanSessionId);
        if (ObjectUtil.isNull(raw)) {
            throw new JbkException("扫码会话已过期，请重新扫码", MiniRejectCode.SCAN_SESSION_EXPIRED);
        }
        JSONObject session = JSONUtil.parseObj(raw.toString());
        // 会话归属校验：会话铸造人必须等于当前登录人，防甲的 session 被乙使用（铁律6）
        if (ObjectUtil.notEqual(userId, session.getLong("userId"))) {
            throw new JbkException("扫码会话无效，请重新扫码", MiniRejectCode.SCAN_SESSION_EXPIRED);
        }
        return session;
    }

    private WsDeviceOutlet requireOutlet(Long outletId) {
        WsDeviceOutlet outlet = ObjectUtil.isNull(outletId) ? null : outletMapper.selectById(outletId);
        return requireNonNull(outlet, "出水口不存在或已下线");
    }

    private WsDevice requireDevice(Long deviceId) {
        WsDevice device = ObjectUtil.isNull(deviceId) ? null : deviceMapper.selectById(deviceId);
        return requireNonNull(device, "设备不存在或已下线");
    }

    private <T> T requireNonNull(T obj, String msg) {
        if (ObjectUtil.isNull(obj)) {
            throw new JbkException(msg, MiniRejectCode.QR_EXPIRED);
        }
        return obj;
    }

    /**
     * 出水口摘要：档案字段（编号、可用性）取当前档案，<b>水种与单价一律取扫码冻结报价</b>。
     * 混着取就会出现「当前水种 + 扫码时价格」这种页面上无法自洽的组合。
     */
    private OutletSummaryVo buildOutletSummary(WsDeviceOutlet outlet, ScanSessionInfo frozen) {
        return new OutletSummaryVo()
                .setOutletId(outlet.getId())
                .setOutletNo(outlet.getOutletNo())
                .setWaterTypeId(frozen.getWaterTypeId())
                .setWaterTypeName(outlet.getWaterType())
                .setUnitPriceFenPerLiter(frozen.getUnitPriceFenPerLiter())
                .setAvailable(ObjectUtil.equal(outlet.getOutletStatus(), 1));
    }

    /**
     * 冻结报价与当前出水口的一致性闸（S2）。水种或单价任一变化即本次会话作废，
     * 用独立拒绝码 SCAN_QUOTE_CHANGED——「会话过期」是时间到了，「报价变化」是内容变了，
     * 合并成一个码用户看到的原因就是错的。
     */
    private void requireQuoteUnchanged(ScanSessionInfo frozen, WsDeviceOutlet outlet) {
        if (ObjectUtil.notEqual(frozen.getWaterTypeId(), outlet.getWaterTypeId())) {
            throw new JbkException("出水口水种已调整，请重新扫码确认", MiniRejectCode.SCAN_QUOTE_CHANGED);
        }
        if (ObjectUtil.notEqual(frozen.getUnitPriceFenPerLiter(), requireOutletPrice(outlet))) {
            throw new JbkException("取水价格已调整，请重新扫码确认", MiniRejectCode.SCAN_QUOTE_CHANGED);
        }
    }

    /** OUTLET_PRICE 规范化：解析规则唯一实现在 WaterBillingMath；本方法只负责把非法配置留痕。 */
    private int requireOutletPrice(WsDeviceOutlet outlet) {
        try {
            return WaterBillingMath.requireOutletPrice(outlet.getOutletPrice());
        }
        catch (JbkException invalid) {
            domainEventService.recordByDevice(OpsEnum.EventType.DEVICE_STATUS, String.valueOf(outlet.getId()), null,
                    "出水口单价配置非法：outletId=" + outlet.getId() + " price=" + outlet.getOutletPrice());
            throw new JbkException("出水口单价配置异常，请联系管理员", MiniRejectCode.QR_EXPIRED);
        }
    }

    private String remainExpiresAt(String scanSessionId) {
        long ttl = RedisUtils.getExpire(redis, SCAN_KEY_PREFIX + scanSessionId);
        int remain = ttl > 0 ? (int) ttl : 0;
        return DateUtils.timeTransition(DateUtils.addDateSeconds(new Date(), remain));
    }

    private String mapOnlineStatus(Integer online) {
        return ObjectUtil.equal(online, 1) ? "ONLINE" : "OFFLINE";
    }

    private String mapRunStatus(Integer run) {
        if (ObjectUtil.equal(run, 1)) {
            return "IDLE";
        }
        if (ObjectUtil.equal(run, 2)) {
            return "DISPENSING";
        }
        if (ObjectUtil.equal(run, 3)) {
            return "FAULT";
        }
        if (ObjectUtil.equal(run, 4)) {
            return "MAINTENANCE";
        }
        if (ObjectUtil.equal(run, 5)) {
            return "LOCKED";
        }
        return "IDLE";
    }
}
