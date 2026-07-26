package com.jbk;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.util.Collections;

public class Generator {
    public static void main(String[] args) {
        gen("xl",
                "",
                "feedback",
                "complaint_feedback");
    }

    //
    public static void gen(String author, String tablePrefix, String packagePrefix, String... table) {
        String dir = System.getProperty("user.dir") + "\\src\\main\\";
        String entityPre = StrUtil.isEmpty(packagePrefix) ? "tool.data.po" : "tool.data." + packagePrefix + ".po";
        String controllerPre = StrUtil.isEmpty(packagePrefix) ? "serve.controller" : "serve.controller." + packagePrefix;
        String servicePre = StrUtil.isEmpty(packagePrefix) ? "serve.service" : "serve.service." + packagePrefix;
        String serviceImplPre = StrUtil.isEmpty(packagePrefix) ? "serve.service.impl" : "serve.service." + packagePrefix + ".impl";
        String mapperPre = StrUtil.isEmpty(packagePrefix) ? "serve.mapper" : "serve.mapper." + packagePrefix;
        String xmlPre = StrUtil.isEmpty(packagePrefix) ? "resources//mapper" : "resources//mapper//" + packagePrefix;
        // 连接信息只从环境变量读，源码里不留任何口令。
        // 此前这里明文硬编码了 root 与生产同款口令——代码生成器是本地开发脚手架，
        // 但它和业务代码一起进仓库、一起分发，口令写在源码里等于把库凭据公开。
        String url = requireEnv("DAKANG_GEN_JDBC_URL");
        String username = requireEnv("DAKANG_GEN_DB_USER");
        String password = requireEnv("DAKANG_GEN_DB_PASSWORD");
        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> {
                    builder.author(author) // 设置作者
                            .enableSpringdoc() // 开启 Springdoc 模式
                            .outputDir(dir + "java") // 指定输出目录
                            .commentDate("yyyy-MM-dd");
                })
                .packageConfig(builder -> {
                    builder.parent("com") // 设置父包名3.
                            .moduleName("jbk") // 设置父包模块名
                            .entity(entityPre)
                            .controller(controllerPre)
                            .service(servicePre)
                            .serviceImpl(serviceImplPre)
                            .mapper(mapperPre)
                            .xml("mapper.xml")
                            .pathInfo(Collections.singletonMap(OutputFile.xml, dir + xmlPre));// 设置mapperXml生成路径
                })
                .strategyConfig(builder -> {
                    builder.addInclude(table) // 设置需要生成的表名
                            .addTablePrefix(tablePrefix); // 设置过滤表前缀
                    builder.entityBuilder()
                            .enableLombok() //开启 lombok
                            .enableChainModel() //开启链式模型
//                            .enableActiveRecord() //开启 ActiveRecord 模型
                            .enableTableFieldAnnotation() //开启生成实体时生成字段注解
                            .idType(IdType.AUTO);  //全局主键类型 设为自增ID

//                            .addTableFills(new Column("create_time", FieldFill.INSERT),
//                                    new Column("create_by", FieldFill.INSERT),
//                                    new Column("update_time", FieldFill.INSERT_UPDATE),
//                                    new Column("update_by", FieldFill.INSERT_UPDATE),
//                                    new Column("data_status", FieldFill.INSERT)) //添加表字段填充 [创建时间] //添加表字段填充、字段修改 [更新时间]
////                            .versionPropertyName("version")
//                            .logicDeleteColumnName("ZT"); //逻辑删除字段名(数据库)
                    builder.controllerBuilder()
                            .enableRestStyle(); //开启生成@RestController 控制器
                    builder.mapperBuilder()
//                            .cache(Cache.class)  //开启 Mybatis二级缓存
                            .enableMapperAnnotation() //开启 @Mapper 注解
                            .enableBaseResultMap() //启用 BaseResultMap 生成
                            .enableBaseColumnList(); //启用 BaseColumnList
                })

                .templateEngine(new FreemarkerTemplateEngine()) // 使用Freemarker引擎模板，默认的是Velocity引擎模板
                .execute();
    }

    /**
     * 读取必需的环境变量。缺失时直接失败并说明怎么配——
     * 绝不回落默认口令：那等于把「忘了配」悄悄变成「连上了某个库」。
     */
    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "缺少环境变量 " + name + "。代码生成器需要显式提供数据库连接，例如：\n"
                    + "  export DAKANG_GEN_JDBC_URL='jdbc:mysql://localhost:3308/dakang?useUnicode=true"
                    + "&characterEncoding=UTF-8&serverTimezone=UTC&nullCatalogMeansCurrent=true'\n"
                    + "  export DAKANG_GEN_DB_USER='root'\n"
                    + "  export DAKANG_GEN_DB_PASSWORD='<你的口令>'");
        }
        return v;
    }
}
