package com.jbk.tool.config.system.mybatis;

import cn.dev33.satoken.stp.StpLogic;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.jbk.tool.consts.ApiConst;
import com.jbk.tool.utils.DateUtils;
import com.jbk.tool.utils.satoken.StpKit;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MpMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        Object createBy = metaObject.getValue("createBy");
        if(ObjectUtil.isNull(createBy)){
            //创建者
            this.setFieldValByName("createBy", getUserId(), metaObject);
        }
        //修改者
        this.setFieldValByName("updateBy", getUserId(), metaObject);
        // 显式业务时钟可能同时锚定订单快照、支付截止时间与审计证据；仅在调用方未赋值时补齐。
        Object createTime = metaObject.getValue("createTime");
        if(ObjectUtil.isNull(createTime)){
            createTime = DateUtils.time();
            this.setFieldValByName("createTime", createTime, metaObject);
        }
        if(ObjectUtil.isNull(metaObject.getValue("updateTime"))){
            // 新增记录的初始更新时间与创建时间同源，避免跨秒产生两套审计时钟。
            this.setFieldValByName("updateTime", createTime, metaObject);
        }
        //状态默认设置成 0 为正常
        this.setFieldValByName("dataStatus", ApiConst.MP_NORMAL, metaObject);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        //修改者
        this.setFieldValByName("updateBy", getUserId(), metaObject);
        //修改时间
        this.setFieldValByName("updateTime", DateUtils.time(), metaObject);
    }

    public long getUserId() {
        // 获取当前会话账号id, 如果未登录，则抛出异常：`NotLoginException`
        try {
            StpLogic stp = StpKit.getStp();
            return stp.getLoginIdAsLong();
        } catch (Exception e) {
        }
        return 0;
    }
}
