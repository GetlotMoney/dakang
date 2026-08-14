package com.jbk.serve.service.mall.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jbk.serve.mapper.mall.WsMallCategoryMapper;
import com.jbk.serve.service.mall.IMallCategoryService;
import com.jbk.tool.consts.mall.MallEnum;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.bo.MallCategoryBo;
import com.jbk.tool.data.mall.bo.MallQueryBo;
import com.jbk.tool.data.mall.bo.MallStatusChangeBo;
import com.jbk.tool.data.mall.po.WsMallCategory;
import com.jbk.tool.data.mall.vo.MallCategoryVo;
import com.jbk.tool.exception.JbkException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商城分类服务实现（E2E-09 S1）。
 *
 * <p>编码唯一由 uk_mall_category_code 物理保证（不含 DATA_STATUS——逻辑删除后
 * 不复用）；停用分类的上架阻断在商品服务（分类停用不影响已上架商品展示）。</p>
 */
@Slf4j
@Service
public class MallCategoryServiceImpl implements IMallCategoryService {

    @Autowired
    private WsMallCategoryMapper categoryMapper;

    @Override
    public PageDataVo<MallCategoryVo> page(MallQueryBo bo) {
        Page<WsMallCategory> page = categoryMapper.selectPage(pageOf(bo),
                Wrappers.lambdaQuery(WsMallCategory.class)
                        .like(ObjectUtil.isNotEmpty(bo.getKeyword()),
                                WsMallCategory::getCategoryName, bo.getKeyword())
                        .eq(bo.getStatus() != null, WsMallCategory::getCategoryStatus, bo.getStatus())
                        .orderByAsc(WsMallCategory::getCategorySort)
                        .orderByAsc(WsMallCategory::getId));
        List<MallCategoryVo> rows = page.getRecords().stream().map(MallCategoryServiceImpl::voOf).toList();
        return new PageDataVo<>(rows, page.getTotal());
    }

    @Override
    public List<MallCategoryVo> listAll() {
        return categoryMapper.selectList(Wrappers.lambdaQuery(WsMallCategory.class)
                        .orderByAsc(WsMallCategory::getCategorySort).orderByAsc(WsMallCategory::getId))
                .stream().map(MallCategoryServiceImpl::voOf).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long save(MallCategoryBo bo) {
        if (ObjectUtil.isEmpty(bo.getCategoryCode())) {
            throw new JbkException("请填写分类编码");
        }
        WsMallCategory row = new WsMallCategory()
                .setCategoryCode(bo.getCategoryCode().trim())
                .setCategoryName(bo.getCategoryName().trim())
                .setCategorySort(bo.getCategorySort())
                .setCategoryStatus(MallEnum.CategoryStatus.ENABLED.getValue());
        try {
            categoryMapper.insert(row);
        }
        catch (DuplicateKeyException e) {
            // 编码唯一且逻辑删除后不复用：撞键即业务冲突，不做“复活旧行”魔法
            throw new JbkException("分类编码已存在（含历史已删除），请更换");
        }
        return row.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(MallCategoryBo bo) {
        if (bo.getId() == null) {
            throw new JbkException("缺少分类信息");
        }
        WsMallCategory existed = categoryMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(existed)) {
            throw new JbkException("分类不存在");
        }
        // 编码不可改（业务唯一标识）；名称与排序可维护
        int updated = categoryMapper.update(null, Wrappers.lambdaUpdate(WsMallCategory.class)
                .eq(WsMallCategory::getId, bo.getId())
                .set(WsMallCategory::getCategoryName, bo.getCategoryName().trim())
                .set(WsMallCategory::getCategorySort, bo.getCategorySort()));
        if (updated != 1) {
            throw new JbkException("分类更新失败，请刷新后重试");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean changeStatus(MallStatusChangeBo bo) {
        if (ObjectUtil.notEqual(bo.getTargetStatus(), MallEnum.CategoryStatus.ENABLED.getValue())
                && ObjectUtil.notEqual(bo.getTargetStatus(), MallEnum.CategoryStatus.DISABLED.getValue())) {
            throw new JbkException("目标状态非法");
        }
        WsMallCategory existed = categoryMapper.selectById(bo.getId());
        if (ObjectUtil.isNull(existed)) {
            throw new JbkException("分类不存在");
        }
        if (ObjectUtil.equal(existed.getCategoryStatus(), bo.getTargetStatus())) {
            return true;
        }
        int updated = categoryMapper.update(null, Wrappers.lambdaUpdate(WsMallCategory.class)
                .eq(WsMallCategory::getId, bo.getId())
                .eq(WsMallCategory::getCategoryStatus, existed.getCategoryStatus())
                .set(WsMallCategory::getCategoryStatus, bo.getTargetStatus()));
        if (updated != 1) {
            throw new JbkException("分类状态已变化，请刷新后重试");
        }
        return true;
    }

    static MallCategoryVo voOf(WsMallCategory row) {
        return new MallCategoryVo()
                .setId(String.valueOf(row.getId()))
                .setCategoryCode(row.getCategoryCode())
                .setCategoryName(row.getCategoryName())
                .setCategorySort(row.getCategorySort())
                .setCategoryStatus(row.getCategoryStatus())
                .setCreateTime(row.getCreateTime());
    }

    static Page<WsMallCategory> pageOf(MallQueryBo bo) {
        long current = bo.getCurrent() == null ? 1 : Math.max(1, bo.getCurrent());
        long size = bo.getSize() == null ? 10 : Math.min(Math.max(1, bo.getSize()), 100);
        return new Page<>(current, size);
    }
}
