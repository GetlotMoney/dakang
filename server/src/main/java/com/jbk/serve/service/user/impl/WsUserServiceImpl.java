package com.jbk.serve.service.user.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jbk.serve.mapper.user.WsUserMapper;
import com.jbk.serve.service.user.IWsUserService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsUserBo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserVo;
import com.jbk.tool.utils.OptionalUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * C 端用户服务。
 */
@Service
public class WsUserServiceImpl extends ServiceImpl<WsUserMapper, WsUser> implements IWsUserService {



    // 获取用户
    @Override
    public WsUserVo getData(Long id) {
        WsUser wsUser = getById(id);
        OptionalUtils.nullToElseThrow(wsUser, "用户信息不存在");
        return BeanUtil.copyProperties(wsUser, WsUserVo.class);
    }

    // 查看用户——通过小程序openid
    @Override
    public WsUserVo getByXcxOpenid(String openid) {
        WsUser wsUser = getOne(Wrappers.lambdaQuery(WsUser.class)
                .eq(WsUser::getWechatXcxOpenid, openid)
        );
        if (ObjectUtil.isNull(wsUser)) {
            return null;
        }
        return BeanUtil.copyProperties(wsUser, WsUserVo.class);
    }

    // 注册——小程序
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WsUserVo registerToXcx(WsUserBo wsUserBo) {
        // 先按手机号查是否已存在
        long count = count(Wrappers.lambdaQuery(WsUser.class)
                .eq(WsUser::getUserPhone, wsUserBo.getUserPhone())
        );
        WsUser wsUser;
        if (count > 0L) {
            wsUser = getOne(Wrappers.lambdaQuery(WsUser.class)
                    .eq(WsUser::getUserPhone, wsUserBo.getUserPhone())
            );
            OptionalUtils.nullToElseThrow(wsUser, "用户信息不存在");
            if (ObjectUtil.isNotEmpty(wsUserBo.getWechatXcxOpenid())) {
                wsUser.setWechatXcxOpenid(wsUserBo.getWechatXcxOpenid());
            }
            updateById(wsUser);
        } else {
            wsUser = BeanUtil.copyProperties(wsUserBo, WsUser.class);
            save(wsUser);
        }
        return BeanUtil.copyProperties(wsUser, WsUserVo.class);
    }

    // 修改信息
    @Override
    public WsUserVo updateData(WsUserBo wsUserBo) {
        long countInfo = count(Wrappers.lambdaQuery(WsUser.class)
                .ne(WsUser::getId, wsUserBo.getId())
                .eq(WsUser::getUserPhone, wsUserBo.getUserPhone())
        );
        OptionalUtils.gtZeroElseThrow(countInfo, "手机号已存在");

        WsUser wsUser = BeanUtil.copyProperties(wsUserBo, WsUser.class);
        updateById(wsUser);
        return BeanUtil.copyProperties(wsUser, WsUserVo.class);
    }


    @Override
    public PageDataVo<WsUserVo> pageData(WsUserBo wsUserBo) {
        Page<WsUser> page = page(new Page<>(wsUserBo.getCurrent(), wsUserBo.getSize()),
                Wrappers.lambdaQuery(WsUser.class)
                        .eq(StrUtil.isNotEmpty(wsUserBo.getUserPhone()), WsUser::getUserPhone, wsUserBo.getUserPhone())
                        .like(StrUtil.isNotEmpty(wsUserBo.getUserName()), WsUser::getUserName, wsUserBo.getUserName())
                        .orderByDesc(WsUser::getId)
        );
        return PageDataVo.getPageData(page, WsUserVo.class);
    }



}
