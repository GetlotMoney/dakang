package com.jbk.serve.service.delivery.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jbk.serve.mapper.delivery.WsDeliveryTaskMapper;
import com.jbk.serve.service.delivery.IDeliveryMediaService;
import com.jbk.serve.service.delivery.IDeliveryTaskTxService;
import com.jbk.serve.service.identity.DemoScenarioService;
import com.jbk.tool.consts.delivery.DeliveryEnum;
import com.jbk.tool.data.delivery.bo.DeliverySignBo;
import com.jbk.tool.data.delivery.po.WsDeliveryTask;
import com.jbk.tool.utils.DateUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 老板测试环境配送模拟器。
 *
 * <p>只替代现实中的第二台配送员手机：待接单、离站、送达和签收全部调用正式
 * {@link IDeliveryTaskTxService}，因此仍执行服务范围、禁止自配送、乐观锁、订单共键、
 * 三照媒体、消息和分润挂点。生产缺省不开启，本组件不会注册。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "delivery.demo-sim.enabled", havingValue = "true")
public class DeliveryDemoSimulator {

    private static final DateTimeFormatter BUSINESS_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private final IDeliveryTaskTxService taskTxService;
    private final IDeliveryMediaService mediaService;
    private final WsDeliveryTaskMapper taskMapper;
    private final DeliveryDemoProfileInitializer profileInitializer;
    private final DemoScenarioService demoScenarioService;

    @Value("${delivery.demo-sim.customer-phone:}")
    private String customerPhone;

    @Value("${delivery.demo-sim.courier-phone:13800001111}")
    private String courierPhone;

    @Value("${delivery.demo-sim.station-code:WS-WH-001}")
    private String stationCode;

    @Value("${delivery.demo-sim.stage-delay-seconds:4}")
    private int stageDelaySeconds;

    /** 每轮只处理当前快照；每个正式状态至少停留 stageDelaySeconds，页面能看见联动过程。 */
    @Scheduled(fixedDelayString = "${delivery.demo-sim.fixed-delay:2000}",
            initialDelayString = "${delivery.demo-sim.initial-delay:3000}")
    public void tick() {
        String now = DateUtils.time();
        try {
            DeliveryDemoProfileInitializer.DemoContext context = profileInitializer.ensureReady(
                    customerPhone, courierPhone, stationCode, now);
            if (context == null) {
                return;
            }
            if (!demoScenarioService.deliveryAutoEnabled(context.customerUserId())) {
                return;
            }
            acceptAvailable(context, now);
            advanceAssigned(context, now);
        } catch (RuntimeException error) {
            // 模拟器失败不能拖垮正式业务线程；任务仍停在最后一个真实成功状态，可人工继续。
            log.warn("老板测试配送模拟器本轮失败：{}", error.getMessage());
        }
    }

    private void acceptAvailable(DeliveryDemoProfileInitializer.DemoContext context, String now) {
        for (WsDeliveryTask task : taskTxService.listAvailableTasks(context.courierUserId(), now)) {
            // 只代办老板测试账号的订单；其他用户任务保留给页面上的配送员手动实点。
            if (!context.customerUserId().equals(task.getUserId())) {
                continue;
            }
            try {
                taskTxService.acceptTask(task.getTaskNo(), task.getVersion(), context.courierUserId(), now);
                log.info("老板测试配送员已接单：taskNo={}", task.getTaskNo());
            } catch (RuntimeException race) {
                log.info("老板测试配送接单跳过：taskNo={} reason={}", task.getTaskNo(), race.getMessage());
            }
        }
    }

    private void advanceAssigned(DeliveryDemoProfileInitializer.DemoContext context, String now) {
        List<WsDeliveryTask> tasks = taskMapper.selectList(Wrappers.lambdaQuery(WsDeliveryTask.class)
                .eq(WsDeliveryTask::getCourierId, context.courierId())
                .in(WsDeliveryTask::getTaskStatus,
                        DeliveryEnum.TaskStatus.ACCEPTED.getValue(),
                        DeliveryEnum.TaskStatus.DELIVERING.getValue(),
                        DeliveryEnum.TaskStatus.ARRIVED.getValue())
                .orderByAsc(WsDeliveryTask::getId));
        for (WsDeliveryTask task : tasks) {
            try {
                advanceOne(context, task, now);
            } catch (RuntimeException error) {
                log.warn("老板测试配送推进失败：taskNo={} status={} reason={}",
                        task.getTaskNo(), task.getTaskStatus(), error.getMessage());
            }
        }
    }

    private void advanceOne(DeliveryDemoProfileInitializer.DemoContext context, WsDeliveryTask task, String now) {
        if (task.getTaskStatus() == DeliveryEnum.TaskStatus.ACCEPTED.getValue()
                && stageReady(task.getAcceptTime(), now, stageDelaySeconds)) {
            taskTxService.advanceTask(task.getTaskNo(), DeliveryEnum.TaskStatus.DELIVERING.getValue(),
                    task.getVersion(), context.courierUserId(), now);
            log.info("老板测试配送员已离站：taskNo={}", task.getTaskNo());
            return;
        }
        if (task.getTaskStatus() == DeliveryEnum.TaskStatus.DELIVERING.getValue()
                && stageReady(task.getDepartTime(), now, stageDelaySeconds)) {
            taskTxService.advanceTask(task.getTaskNo(), DeliveryEnum.TaskStatus.ARRIVED.getValue(),
                    task.getVersion(), context.courierUserId(), now);
            log.info("老板测试配送员已送达：taskNo={}", task.getTaskNo());
            return;
        }
        if (task.getTaskStatus() == DeliveryEnum.TaskStatus.ARRIVED.getValue()
                && stageReady(task.getArriveTime(), now, stageDelaySeconds)) {
            taskTxService.signTask(buildSign(context, task, now), context.courierUserId(), now);
            log.info("老板测试配送已签收：taskNo={}", task.getTaskNo());
        }
    }

    private DeliverySignBo buildSign(DeliveryDemoProfileInitializer.DemoContext context,
                                     WsDeliveryTask task, String now) {
        DeliverySignBo bo = new DeliverySignBo();
        bo.setTaskNo(task.getTaskNo());
        bo.setExpectedVersion(task.getVersion());
        bo.setActualDeliveryCount(task.getDeliveryCount());
        bo.setActualReturnCount(task.getPlanReturnCount() == null ? 0 : task.getPlanReturnCount());
        bo.setLocationStatus(DeliveryEnum.LocationStatus.UNRECORDED.getValue());
        List<DeliverySignBo.SignPhotoBo> photos = new ArrayList<>();
        for (int type : DeliveryEnum.SIGN_PHOTO_TYPES) {
            DeliverySignBo.SignPhotoBo photo = new DeliverySignBo.SignPhotoBo();
            photo.setType(type);
            photo.setMediaKey(mediaService.register(context.courierUserId(), DeliveryEnum.MediaPurpose.SIGN_PHOTO,
                    demoPng(task.getTaskNo(), type), "image/png", now));
            photos.add(photo);
        }
        bo.setPhotos(photos);
        return bo;
    }

    static boolean stageReady(String stageTime, String now, int delaySeconds) {
        if (stageTime == null || now == null || delaySeconds < 0) {
            return false;
        }
        try {
            LocalDateTime start = LocalDateTime.parse(stageTime, BUSINESS_TIME);
            LocalDateTime current = LocalDateTime.parse(now, BUSINESS_TIME);
            return !current.isBefore(start.plusSeconds(delaySeconds));
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    static byte[] demoPng(String taskNo, int type) {
        byte[] suffix = ("DEMO:" + taskNo + ":" + type).getBytes(StandardCharsets.UTF_8);
        byte[] content = Arrays.copyOf(ONE_PIXEL_PNG, ONE_PIXEL_PNG.length + suffix.length);
        System.arraycopy(suffix, 0, content, ONE_PIXEL_PNG.length, suffix.length);
        return content;
    }
}
