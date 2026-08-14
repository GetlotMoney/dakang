package com.jbk.serve.service.mini.auth;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.tool.exception.ErrorMsg;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 绑号闸：未绑手机号的账号不得触碰资金与履约归属类动作。
 * 闸挂在「花钱/收钱/出金/接单」业务原语上而非 URL 黑名单——黑名单下新端点会静默漏闸且无测试变红；
 * 实时读库而非把标记烤进 Token——补绑刻意不换会话，Token 里的 phoneBound 会永远停在补绑前；
 * 必须后端强制——未绑号账号持有完全合法的会话，可绕过 UI 直接 curl /mini/**。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MiniPhoneGate {

    private final WsUserIdentityMapper identityMapper;

    /**
     * 要求该账号已绑定手机号，否则以独立错误码拒绝。
     *
     * <p>身份读取与 {@code MiniAuthServiceImpl.currentContext} 同源（跨全部 DATA_STATUS 按主键回查），
     * 因此已删除/禁用账号在这里的表现与别处一致，不会出现「闸放行了但别处拒绝」的分叉。</p>
     *
     * @param userId 登录人；为空即无会话，属调用方缺陷，一律拒绝
     * @param scene  业务场景名，只进日志与异常文案，便于定位是哪条链撞的闸
     */
    public void requirePhoneBound(Long userId, String scene) {
        if (ObjectUtil.isNull(userId)) {
            // 走到这里说明调用方没拿到登录人却仍在推进资金动作，比未绑号更严重
            throw new JbkException("请先登录后再操作", ErrorMsg.PHONE_BIND_REQUIRED.getCode());
        }
        // 只取手机号这一列：闸挂在每条资金链上，拉回整行 User 既多余，
        // 又会把 ws_user 的全部列变成所有资金链的硬依赖
        String phone = identityMapper.selectPhoneByIdIncludingDeleted(userId);
        // 空串与 NULL 都判未绑：建号链写的是 NULL（空串之间会互撞 uk_user_phone），
        // 但历史数据不保证，两者一起挡住才不会留缝。
        // 账号不存在时查询返回 null，同样落进这个分支——不存在的账号更不该放行。
        if (StrUtil.isBlank(phone)) {
            log.info("绑号闸拦截 userId={} scene={}", userId, scene);
            throw new JbkException(ErrorMsg.PHONE_BIND_REQUIRED);
        }
    }

    /** 只问不拦：供展示层判断是否要提前提示绑号，不作为安全判据。 */
    public boolean isPhoneBound(Long userId) {
        if (ObjectUtil.isNull(userId)) {
            return false;
        }
        return StrUtil.isNotBlank(identityMapper.selectPhoneByIdIncludingDeleted(userId));
    }
}
