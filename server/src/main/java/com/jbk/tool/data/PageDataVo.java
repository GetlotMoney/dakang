/**
 * Copyright (c) 2018 人人开源 All rights reserved.
 * <p>
 * https://www.renren.io
 * <p>
 * 版权所有, 侵权必究!
 */

package com.jbk.tool.data;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页工具类
 *
 * @author xs
 */
@Data
public class PageDataVo<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "列表数据")
    private List<T> list;

    /**
     * 分页
     *
     * @param list  列表数据
     * @param total 总记录数
     */
    public PageDataVo(List<T> list, long total) {
        if (ObjectUtil.isNull(list)) {
            this.list = Lists.newArrayList();
        } else {
            this.list = list;
        }
        this.total = total;
    }

    public static <T> PageDataVo<T> getPageData(List<T> list, long total) {
        return new PageDataVo(list, total);
    }

    public static <T, R> PageDataVo<T> getPageData(Page<R> page, Class<T> cls) {
        List<R> records = page.getRecords();
        if (ObjectUtil.isEmpty(records)) {
            return getPageData(Lists.newArrayList(), page.getTotal());
        }
        List<T> ts = BeanUtil.copyToList(records, cls);
        return getPageData(ts, page.getTotal());
    }

    public static <T> PageDataVo<T> getPageData(Page<T> page) {
        return getPageData(page.getRecords(), page.getTotal());
    }
}
