package com.jbk.serve.service.mini;

import com.jbk.tool.data.mini.bo.MiniTestLoginBo;
import com.jbk.tool.data.mini.vo.MiniAuthResultVo;

/**
 * 测试登录（仅隔离测试环境，与 Pay-Sim 同一开关门控）。
 *
 * <p>存在的唯一理由：九个 headless e2e 跑批脚本无法产出真实 wx code，只能按手机号直签会话跑通资金链（AppID 已认证、正式登录已接入，与凭据是否配置无关）。
 * {@code uni.login} → code2session，于是接真业务域一个都点不到。</p>
 *
 * <p>它<b>不是</b>旧 dev-auth 的回归：<br>
 * · 只为库里<b>已存在且可用</b>的真实账号签发会话，不新建账号、不补身份；<br>
 * · 只收手机号，不收 userId——不给"输入 1 就变成任意用户"的开关；<br>
 * · 复用与正式登录完全相同的 {@code assertUsable} 判定与会话签发器，
 *   删除/禁用/注销账号一律拒绝；<br>
 * · 与 Pay-Sim 同一个 {@code mini.test-login.enabled} 门控，开关关着时 Bean 与路由都不存在。</p>
 */
public interface IMiniTestLoginService {

    MiniAuthResultVo login(MiniTestLoginBo bo);
}
