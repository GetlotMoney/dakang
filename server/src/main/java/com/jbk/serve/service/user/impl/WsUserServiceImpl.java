package com.jbk.serve.service.user.impl;


import cn.hutool.core.bean.BeanUtil;
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
