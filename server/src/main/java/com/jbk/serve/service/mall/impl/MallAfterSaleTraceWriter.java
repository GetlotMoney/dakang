package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.jbk.serve.mapper.mall.WsMallAfterSaleTraceMapper;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.mall.po.WsMallAfterSale;
import com.jbk.tool.data.mall.po.WsMallAfterSaleTrace;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 售后轨迹唯一写入口（E2E-09 S4）：售后状态由三处不同事务边界推进，各写一份轨迹必然口径分叉。
 * fail-closed：幂等键被占用一律抛出回滚——CAS 命中者唯一，键却已被占只能是伪造或被删悬空的证据。
 *
 * @author dakang
 * @since 2026-08-11
 */
@Slf4j
@Component
public class MallAfterSaleTraceWriter {

    /** 售后轨迹幂等键前缀：MAT:&lt;afterSaleNo&gt;:&lt;节点值&gt;。 */
    static final String TRACE_KEY_PREFIX = "MAT:";

    private static final long SYSTEM_OPERATOR = 0L;

    @Autowired
    private WsMallAfterSaleTraceMapper traceMapper;

    /**
     * 写一个状态到达节点。
     *
     * @param subjectId 动作指向的对象（如被分配方）；无对象时传 null，不要拿 actorId 顶替
     */
    public void write(WsMallAfterSale afterSale, MallEnum.AfterSaleStatus node,
                      MallEnum.ActorType actorType, Long actorId, Long subjectId,
                      String now, String text) {
        String key = TRACE_KEY_PREFIX + afterSale.getAfterSaleNo() + ":" + node.getValue();
        if (ObjectUtil.isNotNull(traceMapper.selectByKeyIncludingDeleted(key))) {
            throw new JbkException("售后轨迹证据冲突（节点已被占用），动作已中止，请人工核查");
        }
        WsMallAfterSaleTrace trace = new WsMallAfterSaleTrace()
                .setAfterSaleId(afterSale.getId())
                .setAfterSaleNo(afterSale.getAfterSaleNo())
                .setTraceNode(node.getValue())
                .setActorType(actorType.getValue())
                .setActorId(actorId == null ? SYSTEM_OPERATOR : actorId)
                .setSubjectId(subjectId)
                .setTraceTime(now)
                .setTraceText(text)
                .setBizIdempotencyKey(key);
        trace.setCreateTime(now);
        try {
            traceMapper.insert(trace);
        }
        catch (DuplicateKeyException race) {
            throw new JbkException("售后轨迹证据冲突（并发写入），动作已中止，请人工核查");
        }
    }
}
