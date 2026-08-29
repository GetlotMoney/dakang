package com.jbk.serve.service.settlement.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.settlement.WsIncomeAccountMapper;
import com.jbk.serve.mapper.settlement.WsIncomeFlowMapper;
import com.jbk.serve.service.settlement.IIncomeService;
import com.jbk.tool.consts.settlement.SettlementEnum;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.data.settlement.po.WsIncomeAccount;
import com.jbk.tool.data.settlement.po.WsIncomeFlow;
import com.jbk.tool.data.settlement.po.WsSplitRecord;
import com.jbk.tool.data.mini.vo.MiniWalletVo;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收益账户服务实现。加入调用方事务（Worker 的推进事务）：入账失败整体回滚，
 * 绝不出现「分账已完成、钱包没到账」的中间态。
 *
 * @author dakang
 * @since 2026-07-31
 */
@Slf4j
@Service
public class IncomeServiceImpl extends ServiceImpl<WsIncomeAccountMapper, WsIncomeAccount> implements IIncomeService {

    /** 绑号闸：出金必须可联系、可追溯。 */
    @Autowired
    private com.jbk.serve.service.mini.auth.MiniPhoneGate phoneGate;
    @Autowired
    private WsIncomeFlowMapper flowMapper;
    @Autowired
    private com.jbk.serve.mapper.settlement.WsSplitRecordMapper splitRecordMapper;

    /**
     * 冻结期天数（D-421）：与 SplitServiceImpl 读同一配置键。刻意不注入 ISplitService——
     * settleOne 已依赖 IIncomeService（分账入账），反向注入即循环依赖（部署实测炸启动，
     * 测试 Ctx 手动装配不做环检测所以没抓到）。同 key 双读点由注释互指看守。
     */
    @org.springframework.beans.factory.annotation.Value("${settlement.split-freeze-days:1}")
    private int splitFreezeDays;

    @Override
    public boolean creditFromSplit(Long splitId, Long userId, long amountFen, String orderNo) {
        if (splitId == null || userId == null || amountFen < 0) {
            throw new JbkException("分润入账参数不完整");
        }
        if (amountFen == 0) {
            // 零元行（比例 0 或整除归零）不产流水：账户无变动，流水表不留零值噪音
            return false;
        }
        // 行锁而非乐观 CAS：删流水重试的路子有两处死穴——@TableLogic 让「删自己刚插的行」
        // 变成逻辑删除，行仍占着 uk_income_flow_biz_key，重插必撞键被误判「已入过账」，
        // 分润静默丢失；且 REPEATABLE READ 下同事务重读拿的仍是旧快照，重试注定全败。
        // FOR UPDATE 锁定读恒取最新已提交余额，与提现互斥串行，一次成功、无重试路径。
        ensureAccount(userId);
        WsIncomeAccount account = baseMapper.selectByUserIdForUpdate(userId);
        long balance = account.getBalanceFen();
        long afterCredit = balance + amountFen;
        // 流水先行：幂等键撞键即已入过账（唯一事实判据，先于余额变动）
        WsIncomeFlow flow = new WsIncomeFlow()
                .setUserId(userId)
                .setFlowType(SettlementEnum.IncomeFlowType.SPLIT_IN.getValue())
                .setAmountFen(amountFen)
                .setAfterFen(afterCredit)
                .setSplitId(splitId)
                .setOrderNo(orderNo)
                .setBizIdempotencyKey("INCOME:" + splitId);
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException e) {
            return false;
        }
        // D-420 补差优先：存在冲减待补差额时，本次入账先补差额、剩余才进可用余额。
        // 抵扣单独成一条流水（负额），账本 AFTER 链保持连续；补足后提现限制自然解除。
        long deficit = account.getClawbackDeficitFen() == null ? 0L : account.getClawbackDeficitFen();
        long offset = Math.min(deficit, amountFen);
        long finalBalance = afterCredit - offset;
        if (offset > 0) {
            WsIncomeFlow offsetFlow = new WsIncomeFlow()
                    .setUserId(userId)
                    .setFlowType(SettlementEnum.IncomeFlowType.SPLIT_REVERSE.getValue())
                    .setAmountFen(-offset)
                    .setAfterFen(finalBalance)
                    .setSplitId(splitId)
                    .setOrderNo(orderNo)
                    .setBizIdempotencyKey("DEFICIT-OFFSET:" + splitId)
                    .setFlowRemark("分润入账抵扣冲减待补差额（D-420）");
            try {
                flowMapper.insert(offsetFlow);
            }
            catch (DuplicateKeyException e) {
                // INCOME: 键已挡住重放，同 splitId 的抵扣键撞键=证据链断裂
                throw new JbkException("补差流水幂等键冲突，入账中止");
            }
        }
        // 行锁在手，按主键直更即安全；仍递增 VERSION 供提现侧乐观读者感知变化
        boolean updated = update(Wrappers.lambdaUpdate(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getId, account.getId())
                .set(WsIncomeAccount::getBalanceFen, finalBalance)
                .set(WsIncomeAccount::getClawbackDeficitFen, deficit - offset)
                .set(WsIncomeAccount::getVersion, account.getVersion() + 1));
        if (!updated) {
            throw new JbkException("收益入账写入失败");
        }
        return true;
    }

    @Override
    public MiniWalletVo walletFor(Long userId) {
        // 本方法是**只读**展示，不挂绑号闸：未绑号用户看得到自己的分润余额是正常的，
        // 拦住只读会让「我的-收益钱包」对全部游客态用户直接报错，而他们什么都还没做。
        // 闸挂在真正出账的 applyWithdraw 上。
        WsIncomeAccount account = getOne(Wrappers.lambdaQuery(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getUserId, userId));
        List<WsIncomeFlow> flows =
                flowMapper.selectList(Wrappers.lambdaQuery(WsIncomeFlow.class)
                        .eq(WsIncomeFlow::getUserId, userId)
                        .orderByDesc(WsIncomeFlow::getId)
                        .last("LIMIT 50"));
        List<Long> splitIds = flows.stream().map(WsIncomeFlow::getSplitId)
                .filter(ObjectUtil::isNotNull).distinct().toList();
        Map<Long, WsSplitRecord> splitById = splitIds.isEmpty() ? Map.of()
                : splitRecordMapper.selectBatchIds(splitIds).stream()
                .collect(Collectors.toMap(WsSplitRecord::getId, Function.identity()));
        // D-421 在途分润（R1-P2 整改）：SUM/MIN 下沉 SQL 聚合，不把全部行拉进 JVM——
        // 旧写法全表扫描且随全平台分账量退化（复验 EXPLAIN type=ALL 实测）。只聚合
        // SPLIT_AMOUNT>0：零元行不计在途、不许把最早解冻时间提前。平台行
        // receiverUserId=0 哨兵不会命中任何真实 userId，无需额外排除。
        com.jbk.serve.mapper.settlement.WsSplitRecordMapper.PendingSplitAgg agg =
                splitRecordMapper.aggregatePendingByReceiver(userId);
        long pendingFen = (agg == null || agg.getPendingFen() == null) ? 0L : agg.getPendingFen();
        String earliestUnfreeze = null;
        if (agg != null && agg.getEarliestCreateTime() != null) {
            try {
                earliestUnfreeze = java.time.LocalDateTime
                        .parse(agg.getEarliestCreateTime(), DateUtils.COMPACT_FORMATTER)
                        .plusDays(Math.max(0, splitFreezeDays))
                        .format(DateUtils.COMPACT_FORMATTER);
            }
            catch (Exception e) {
                // 展示口径：脏时间不许炸钱包接口——解冻时间降级不展示，金额仍准确
                log.warn("在途分润最早创建时间非法，解冻时间降级：user={} value={}",
                        userId, agg.getEarliestCreateTime());
            }
        }
        long deficitFen = ObjectUtil.isNull(account) || account.getClawbackDeficitFen() == null
                ? 0L : account.getClawbackDeficitFen();
        return new MiniWalletVo()
                .setBalanceFen(ObjectUtil.isNull(account) ? 0L : account.getBalanceFen())
                .setFrozenFen(ObjectUtil.isNull(account) ? 0L : account.getFrozenFen())
                .setClawbackDeficitFen(deficitFen)
                .setPendingSplitFen(pendingFen)
                .setEarliestUnfreezeTime(earliestUnfreeze)
                .setRoleSummaries(splitRecordMapper.aggregateRoleIncomeByReceiver(userId).stream()
                        .map(row -> new MiniWalletVo.RoleSummary()
                                .setReceiverType(row.getReceiverType())
                                .setSettledFen(ObjectUtil.defaultIfNull(row.getSettledFen(), 0L))
                                .setPendingFen(ObjectUtil.defaultIfNull(row.getPendingFen(), 0L)))
                        .toList())
                .setFlows(flows.stream().map(flow -> new MiniWalletVo.Flow()
                        .setFlowType(flow.getFlowType())
                        .setAmountFen(flow.getAmountFen())
                        .setAfterFen(flow.getAfterFen())
                        .setOrderNo(flow.getOrderNo())
                        .setCreateTime(flow.getCreateTime())
                        .setReceiverType(flow.getSplitId() == null || splitById.get(flow.getSplitId()) == null
                                ? null : splitById.get(flow.getSplitId()).getReceiverType())).toList())
                .setEvidenceMode("real");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyWithdraw(Long userId, long amountFen, String requestId) {
        if (amountFen <= 0 || StrUtilLike.isBlankUuid(requestId)) {
            throw new JbkException("提现金额必须为正且请求号为规范 UUID");
        }
        // 幂等判据必须先于余额检查：冻结成功后余额已变，重放若先撞「余额不足」
        // 会把一次已成功的申请误报为失败。重放要语义核验（键是全局命名空间，
        // 他人请求号或改参重放静默吞掉=调用方以为冻结成功而账面分文未动）
        if (ObjectUtil.isNotNull(flowByKey("WITHDRAW:" + requestId))) {
            requireSameReplay("WITHDRAW:" + requestId, userId, -amountFen, "提现申请");
            return;
        }

        // 绑号闸：提现是唯一的资金出账方向，钱要打给一个平台联系得上的人。
        // 位置刻意在幂等重放判据之后——放在它之前的话，一个已成功冻结的请求重放时会撞闸，
        // 把一次已完成的申请误报成失败。
        //
        // 这里曾被错挂到只读的 walletFor 上（两个方法都以同一句 getOne(...) 开头，
        // 挂载脚本匹配到了第一个），后果是判据与动作完全倒置：看不了余额，却提得了现。
        phoneGate.requirePhoneBound(userId, "分润提现");

        WsIncomeAccount account = getOne(Wrappers.lambdaQuery(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getUserId, userId));
        // D-420 提现闸先于余额判断：存在冲减待补差额时无论余额多少一律拒绝——差额由
        // 后续分润入账优先补足，补足即解除。CAS 前态含 VERSION：冲减即使余额零变动
        // 也递增版本，无读旧放行窗口
        if (ObjectUtil.isNotNull(account) && account.getClawbackDeficitFen() != null
                && account.getClawbackDeficitFen() > 0) {
            throw new JbkException("分润存在退款扣回待补差额，暂不可提现（后续收益将优先补足）");
        }
        if (ObjectUtil.isNull(account) || account.getBalanceFen() < amountFen) {
            throw new JbkException("可用分润余额不足");
        }
        // 流水先行：库层唯一键兜底「判据读取与插入之间」的并发同请求窗口
        WsIncomeFlow flow = new WsIncomeFlow()
                .setUserId(userId)
                .setFlowType(SettlementEnum.IncomeFlowType.WITHDRAW_FREEZE.getValue())
                .setAmountFen(-amountFen)
                .setAfterFen(account.getBalanceFen() - amountFen)
                .setBizIdempotencyKey("WITHDRAW:" + requestId)
                .setFlowRemark("提现申请（后台审核中，本期不做真实出金）");
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException e) {
            requireSameReplay("WITHDRAW:" + requestId, userId, -amountFen, "提现申请");
            return;
        }
        boolean updated = update(Wrappers.lambdaUpdate(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getId, account.getId())
                .eq(WsIncomeAccount::getBalanceFen, account.getBalanceFen())
                .eq(WsIncomeAccount::getVersion, account.getVersion())
                .set(WsIncomeAccount::getBalanceFen, account.getBalanceFen() - amountFen)
                .set(WsIncomeAccount::getFrozenFen, account.getFrozenFen() + amountFen)
                .set(WsIncomeAccount::getVersion, account.getVersion() + 1));
        if (!updated) {
            // CAS 输了整体回滚（含刚插的流水）：让用户重试，绝不出现半迁移
            throw new JbkException("余额并发变动，请重试提现");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeWithdrawSimulated(Long userId, long amountFen, String requestId) {
        if (amountFen <= 0 || StrUtilLike.isBlankUuid(requestId)) {
            throw new JbkException("模拟出金参数不合法");
        }
        String doneKey = "WITHDRAW-DONE:" + requestId;
        if (ObjectUtil.isNotNull(flowByKey(doneKey))) {
            requireSameReplay(doneKey, userId, -amountFen, "模拟出金完成");
            return;
        }
        WsIncomeFlow freeze = flowByKey("WITHDRAW:" + requestId);
        if (ObjectUtil.isNull(freeze) || ObjectUtil.notEqual(freeze.getUserId(), userId)
                || freeze.getAmountFen() != -amountFen) {
            throw new JbkException("模拟出金与原提现冻结事实不一致");
        }
        WsIncomeAccount account = baseMapper.selectByUserIdForUpdate(userId);
        if (ObjectUtil.isNull(account) || account.getFrozenFen() < amountFen) {
            throw new JbkException("提现冻结余额不足，无法完成模拟出金");
        }
        WsIncomeFlow done = new WsIncomeFlow()
                .setUserId(userId)
                .setFlowType(SettlementEnum.IncomeFlowType.WITHDRAW_DONE.getValue())
                .setAmountFen(-amountFen)
                .setAfterFen(account.getBalanceFen())
                .setBizIdempotencyKey(doneKey)
                .setFlowRemark("演示环境模拟打款完成（不触发真实出金）");
        try {
            flowMapper.insert(done);
        }
        catch (DuplicateKeyException e) {
            requireSameReplay(doneKey, userId, -amountFen, "模拟出金完成");
            return;
        }
        boolean updated = update(Wrappers.lambdaUpdate(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getId, account.getId())
                .eq(WsIncomeAccount::getFrozenFen, account.getFrozenFen())
                .eq(WsIncomeAccount::getVersion, account.getVersion())
                .set(WsIncomeAccount::getFrozenFen, account.getFrozenFen() - amountFen)
                .set(WsIncomeAccount::getVersion, account.getVersion() + 1));
        if (!updated) {
            throw new JbkException("账户并发变动，请重试模拟出金");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectWithdraw(Long userId, long amountFen, String requestId, Long operatorId) {
        if (amountFen <= 0 || StrUtilLike.isBlankUuid(requestId)) {
            throw new JbkException("驳回参数不合法");
        }
        // 幂等判据先行：解冻成功后冻结额已归零，重放若先撞「冻结不足」会误报失败
        if (ObjectUtil.isNotNull(flowByKey("WITHDRAW-REJ:" + requestId))) {
            requireSameReplay("WITHDRAW-REJ:" + requestId, userId, amountFen, "提现驳回");
            return;
        }
        // 驳回必须锚定既有冻结事实：提现申请没有独立状态机实体，唯一事实是 WITHDRAW: 流水。
        // 不核验就等于「冻结额够即可任意解冻」——错请求号/错金额解冻后，原申请账面仍呈冻结中，
        // 将来接真实出金按原申请打款即双重放款
        WsIncomeFlow freeze = flowByKey("WITHDRAW:" + requestId);
        if (ObjectUtil.isNull(freeze) || ObjectUtil.notEqual(freeze.getUserId(), userId)
                || freeze.getAmountFen() != -amountFen) {
            throw new JbkException("驳回与原提现申请不符（请求号/收益人/金额三者必须与冻结流水一致）");
        }
        WsIncomeAccount account = getOne(Wrappers.lambdaQuery(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getUserId, userId));
        if (ObjectUtil.isNull(account) || account.getFrozenFen() < amountFen) {
            throw new JbkException("冻结余额不足，无法驳回解冻");
        }
        WsIncomeFlow flow = new WsIncomeFlow()
                .setUserId(userId)
                .setFlowType(SettlementEnum.IncomeFlowType.WITHDRAW_REJECT.getValue())
                .setAmountFen(amountFen)
                .setAfterFen(account.getBalanceFen() + amountFen)
                .setBizIdempotencyKey("WITHDRAW-REJ:" + requestId)
                .setFlowRemark("提现驳回解冻（操作人 " + operatorId + "）");
        try {
            flowMapper.insert(flow);
        }
        catch (DuplicateKeyException e) {
            requireSameReplay("WITHDRAW-REJ:" + requestId, userId, amountFen, "提现驳回");
            return;
        }
        boolean updated = update(Wrappers.lambdaUpdate(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getId, account.getId())
                .eq(WsIncomeAccount::getFrozenFen, account.getFrozenFen())
                .eq(WsIncomeAccount::getVersion, account.getVersion())
                .set(WsIncomeAccount::getBalanceFen, account.getBalanceFen() + amountFen)
                .set(WsIncomeAccount::getFrozenFen, account.getFrozenFen() - amountFen)
                .set(WsIncomeAccount::getVersion, account.getVersion() + 1));
        if (!updated) {
            throw new JbkException("账户并发变动，请重试驳回");
        }
    }

    /** UUID 形态判定（局部小助手，避免为一个正则引全局工具类）。 */
    private static final class StrUtilLike {
        private static boolean isBlankUuid(String value) {
            return value == null || !value.matches("^[0-9a-fA-F-]{36}$");
        }
    }

    private WsIncomeFlow flowByKey(String key) {
        return flowMapper.selectOne(Wrappers.lambdaQuery(WsIncomeFlow.class)
                .eq(WsIncomeFlow::getBizIdempotencyKey, key));
    }

    /** 幂等重放核验：撞键行必须与本次入参同人同金额，否则是改参/冒用重放，明确拒绝。 */
    private void requireSameReplay(String key, Long userId, long amountFen, String action) {
        WsIncomeFlow existing = flowByKey(key);
        if (ObjectUtil.isNull(existing) || ObjectUtil.notEqual(existing.getUserId(), userId)
                || existing.getAmountFen() != amountFen) {
            throw new JbkException(action + "请求号已被占用且与本次参数不符，请更换请求号");
        }
    }

    /** 建户或读回：uk_income_account_user 撞键=并发建户，读回既有行。 */
    private WsIncomeAccount ensureAccount(Long userId) {
        WsIncomeAccount existing = getOne(Wrappers.lambdaQuery(WsIncomeAccount.class)
                .eq(WsIncomeAccount::getUserId, userId));
        if (ObjectUtil.isNotNull(existing)) {
            return existing;
        }
        try {
            WsIncomeAccount fresh = new WsIncomeAccount()
                    .setUserId(userId).setBalanceFen(0L).setFrozenFen(0L).setVersion(1);
            save(fresh);
            return fresh;
        }
        catch (DuplicateKeyException e) {
            return getOne(Wrappers.lambdaQuery(WsIncomeAccount.class)
                    .eq(WsIncomeAccount::getUserId, userId));
        }
    }
}
