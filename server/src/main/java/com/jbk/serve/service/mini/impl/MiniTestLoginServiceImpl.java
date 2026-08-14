package com.jbk.serve.service.mini.impl;

import cn.hutool.core.util.StrUtil;
import com.jbk.serve.mapper.user.WsUserIdentityMapper;
import com.jbk.serve.service.mini.IMiniCapabilityService;
import com.jbk.serve.service.mini.IMiniTestLoginService;
import com.jbk.serve.service.mini.auth.IKhUserSessionIssuer;
import com.jbk.serve.service.mini.auth.KhUserSession;
import com.jbk.serve.service.mini.auth.MiniUserIdentitySupport;
import com.jbk.tool.data.mini.bo.MiniTestLoginBo;
import com.jbk.tool.data.mini.vo.MiniAccountContextVo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.exception.JbkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 测试登录实现（仅 {@code mini.test-login.enabled=true} 时注册）。
 *
 * <p>身份判定与正式登录<b>共用同一套代码</b>（跨全部 DATA_STATUS 查询 + {@code resolveUnique}
 * + {@code assertUsable}），因此删除/禁用/注销账号、以及历史遗留的重复手机号，
 * 在这里和在正式登录里被拒绝的理由与措辞完全一致——不会出现"测试能进、正式进不去"
 * 这种把真实缺陷藏起来的差异。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
// 与 MiniTestLoginController 同一个开关。两者分挂不同开关时，只开一侧会造出
// 「控制器注册了但依赖的 Service 不存在」的启动期失败，或反过来留一个永远没人调的 Bean。
@ConditionalOnProperty(name = "mini.test-login.enabled", havingValue = "true")
public class MiniTestLoginServiceImpl implements IMiniTestLoginService {

    private final WsUserIdentityMapper identityMapper;
    private final IKhUserSessionIssuer sessionIssuer;
    private final IMiniCapabilityService capabilityService;

    @Override
    public MiniAuthResultVo login(MiniTestLoginBo bo) {
        String phone = StrUtil.trimToEmpty(bo.getPhone());
        if (StrUtil.isBlank(phone)) {
            throw new JbkException("参数不完整");
        }
        List<WsUser> rows = identityMapper.selectByPhoneIncludingDeleted(phone);
        WsUser user = MiniUserIdentitySupport.resolveUnique(rows, "手机号身份数据异常，请联系客服");
        if (user == null) {
            // 不新建账号：测试登录只能"进入"已存在的身份，绝不制造身份
            throw new JbkException("该手机号没有对应账号，测试登录不会创建账号");
        }
        MiniUserIdentitySupport.assertUsable(user);

        // 留痕：测试会话签发是一件必须能在日志里查到的事，绝不静默发 token
        log.warn("【测试登录】为已存在账号签发 KH_USER 会话：userId={} phone={}（仅测试环境，mini.test-login.enabled 开启）",
                user.getId(), StrUtil.hide(phone, 3, 7));

        KhUserSession token = sessionIssuer.issue(user.getId(), user.getUserName());
        MiniAccountContextVo ctx = new MiniAccountContextVo();
        ctx.setAccountId(String.valueOf(user.getId()));
        ctx.setUserId(String.valueOf(user.getId()));
        ctx.setUserName(user.getUserName());
        ctx.setUserPhone(user.getUserPhone());
        ctx.setPhoneBound(StrUtil.isNotBlank(user.getUserPhone()));
        // 与正式登录同一能力投影实现：测试会话看到的入口与正式会话完全一致，不藏差异
        ctx.setCapabilities(capabilityService.capabilitiesOf(user.getId()));
        ctx.setOwnerScope(capabilityService.ownerScopeOf(user.getId()));
        return MiniAuthResultVo.bound(token.tokenName(), token.tokenValue(), ctx);
    }
}
