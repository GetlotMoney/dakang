package com.jbk.serve.service.mall.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbk.tool.config.system.json.JacksonConfig;
import com.jbk.tool.data.PageDataVo;
import com.jbk.tool.data.mall.vo.MallStockFlowVo;
import com.jbk.tool.data.mall.vo.MallStockVo;
import com.jbk.tool.data.mall.vo.MiniMallHomeVo;
import com.jbk.tool.data.mall.vo.MiniMallProductDetailVo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商城响应的真实 Jackson 输出形态（R1-P0-2 证据固化）。
 *
 * <p>JacksonConfig 对 Long/long 全局挂 ToStringSerializer：真实响应里所有 Long
 * 字段都是十进制字符串（{@code "minSalePriceFen":"2400"}），Integer 字段仍是
 * 裸数字。前端归一化层"同时接受安全整数 number 与规范十进制字符串"的双形态
 * 契约以本测试为锚——若有人移除该序列化器或改动形态，这里先红。</p>
 */
@DisplayName("商城 Jackson 序列化形态")
class MallJacksonShapeTest {

    /** 与线上完全同源：直接用 JacksonConfig 构造的 ObjectMapper。 */
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    @DisplayName("小程序首页卡：Long 金额输出为字符串")
    void homeCardLongAsString() throws Exception {
        MiniMallHomeVo.ProductCard card = new MiniMallHomeVo.ProductCard()
                .setProductId("12")
                .setProductName("整箱水")
                .setCategoryId("3")
                .setMinSalePriceFen(2400L)
                .setInStock(true);
        String json = mapper.writeValueAsString(card);
        assertTrue(json.contains("\"minSalePriceFen\":\"2400\""),
                "Long 金额必须是字符串形态：" + json);
        assertTrue(json.contains("\"inStock\":true"), "布尔保持裸值：" + json);
    }

    @Test
    @DisplayName("小程序 SKU：价格与重量都是 Long 字符串，规格是纯 string 对象")
    void skuItemLongAsString() throws Exception {
        MiniMallProductDetailVo.SkuItem sku = new MiniMallProductDetailVo.SkuItem()
                .setSkuId("77")
                .setSkuName("单桶")
                .setSpecs(Map.of("规格", "18.9L"))
                .setSalePriceFen(1800L)
                .setMarketPriceFen(2000L)
                .setWeightGram(19500L)
                .setInStock(true);
        String json = mapper.writeValueAsString(sku);
        assertTrue(json.contains("\"salePriceFen\":\"1800\""), json);
        assertTrue(json.contains("\"marketPriceFen\":\"2000\""), json);
        assertTrue(json.contains("\"weightGram\":\"19500\""), json);
        assertTrue(json.contains("\"规格\":\"18.9L\""), json);
    }

    @Test
    @DisplayName("分页壳：total 是 Long 字符串——PC 分页组件必须先归一再消费")
    void pageTotalLongAsString() throws Exception {
        PageDataVo<MiniMallHomeVo.CategoryItem> page = PageDataVo.getPageData(List.of(), 135L);
        String json = mapper.writeValueAsString(page);
        assertTrue(json.contains("\"total\":\"135\""),
                "分页 total 必须是字符串形态：" + json);
    }

    @Test
    @DisplayName("库存与流水：Long 数量是字符串（出库增减带负号），Integer 字段保持裸数字")
    void stockAndFlowShapes() throws Exception {
        MallStockVo stock = new MallStockVo()
                .setId("9").setWarehouseId("1").setSkuId("7")
                .setAvailableQty(120L).setReservedQty(0L).setVersion(3);
        String stockJson = mapper.writeValueAsString(stock);
        assertTrue(stockJson.contains("\"availableQty\":\"120\""), stockJson);
        assertTrue(stockJson.contains("\"reservedQty\":\"0\""), stockJson);
        assertTrue(stockJson.contains("\"version\":3"), "Integer 必须保持裸数字：" + stockJson);

        MallStockFlowVo flow = new MallStockFlowVo()
                .setId("11").setFlowType(2)
                .setAvailableChange(-3L).setReservedChange(0L)
                .setAvailableAfter(117L).setReservedAfter(0L);
        String flowJson = mapper.writeValueAsString(flow);
        assertTrue(flowJson.contains("\"availableChange\":\"-3\""),
                "带符号 Long 也是字符串形态：" + flowJson);
        assertTrue(flowJson.contains("\"availableAfter\":\"117\""), flowJson);
        assertTrue(flowJson.contains("\"flowType\":2"), "Integer 必须保持裸数字：" + flowJson);
    }
}
