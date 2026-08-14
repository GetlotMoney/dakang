package com.jbk.serve.service.mini.auth;

import com.jbk.serve.mapper.mini.WsIdentityConflictMapper;
import com.jbk.tool.consts.mini.MiniIdentityConflictEnum;
import com.jbk.tool.data.mini.po.WsIdentityConflict;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.PhoneMask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 身份冲突留痕：把 fail-closed 的拒绝变成运营可认领的台账。
 * 必须 REQUIRES_NEW——调用点随后就抛异常让主事务回滚（回滚正是拒绝的业务结果），
 * 同事务留痕会连证据一起滚掉。留痕失败绝不外抛：宁可丢一条台账，不可改变业务结论。
 * 先 UPDATE 累加再 INSERT，撞唯一键补一次累加——常见路径一次 UPDATE，并发不插重复行。
 *
 * @author dakang
 * @since 2026-08-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MiniIdentityConflictRecorder {

    /** 系统留痕的操作人：这条记录不是任何员工写的。 */
    private static final long SYSTEM_ACTOR = 0L;

    private final WsIdentityConflictMapper conflictMapper;

    /**
     * 记录一次身份冲突。
     *
     * @param type        冲突类型
     * @param scene       发生场景
     * @param holderUserId 当前持有该身份的账号；未知时传 {@code null}（记 0 哨兵）
     * @param actorUserId  发起方账号；票据路径建号前传 {@code null}
     * @param rawPhone     原始手机号，<b>本方法内部脱敏后才落库</b>，调用方不必先处理
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public void record(MiniIdentityConflictEnum.Type type,
                       MiniIdentityConflictEnum.Scene scene,
                       Long holderUserId,
                       Long actorUserId,
                       String rawPhone) {
        try {
            long holder = holderUserId == null ? MiniIdentityConflictEnum.ACTOR_ABSENT : holderUserId;
            long actor = actorUserId == null ? MiniIdentityConflictEnum.ACTOR_ABSENT : actorUserId;
            String masked = PhoneMask.mask(rawPhone);
            String now = DateUtils.time();
            // 键里放脱敏号而不是原号：键会进日志与报表，原号进去等于绕开脱敏
            // PhoneMask.mask 对 null/非 11 位恒返回空串，不会给出 "null" 字面量
            String key = String.join(":", "IDC", type.name(),
                    String.valueOf(holder), String.valueOf(actor), masked);

            if (conflictMapper.bumpOccurrence(key, now) > 0) {
                return;
            }
            WsIdentityConflict row = new WsIdentityConflict()
                    .setConflictKey(key)
                    .setConflictType(type.name())
                    .setOccurScene(scene.name())
                    .setHolderUserId(holder)
                    .setActorUserId(actor)
                    .setMaskedPhone(masked)
                    .setOccurCount(1)
                    .setFirstOccurTime(now)
                    .setLastOccurTime(now)
                    .setHandleStatus(MiniIdentityConflictEnum.HandleStatus.PENDING.getValue());
            row.setCreateBy(SYSTEM_ACTOR);
            row.setCreateTime(now);
            row.setUpdateBy(SYSTEM_ACTOR);
            row.setUpdateTime(now);
            try {
                conflictMapper.insert(row);
            }
            catch (DuplicateKeyException e) {
                // 并发下另一请求刚插过同一键：补一次累加，不重复插行
                conflictMapper.bumpOccurrence(key, now);
            }
        }
        catch (Exception e) {
            // 绝不外抛：留痕失败不能改变"这次请求被拒绝"这个业务结论
            log.error("身份冲突留痕失败 type={} scene={} holder={} actor={}",
                    type, scene, holderUserId, actorUserId, e);
        }
    }
}
