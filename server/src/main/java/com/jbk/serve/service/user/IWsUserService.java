package com.jbk.serve.service.user;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.user.bo.WsUserBo;
import com.jbk.tool.data.user.po.WsUser;
import com.jbk.tool.data.user.vo.WsUserVo;

public interface IWsUserService extends IService<WsUser> {

    PageDataVo<WsUserVo> pageData(WsUserBo wsUserBo);

    WsUserVo getData(Long id);

}
