package com.getjobs.application.service;

import com.getjobs.worker.platform.model.DeliveryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 投递策略的**全局来源** —— 平台无关。
 *
 * <p><b>为什么不在平台上</b>：兜底招呼语、岗位间隔、AI 开关、HR 活跃阈值、是否发图片简历、
 * 同公司去重、调试模式……这些是"这轮投递怎么跑"的规则，跟用哪个网站招人毫无关系。
 * 之前它们挂在 {@code JobPlatform.policy()} 上，只因为数据来源恰好是平台自己的配置表；
 * 现在改为从这里统一提供，平台的接口上就不再出现策略了。
 *
 * <p><b>数据来源</b>：{@code config/boss.yaml} 的 {@code delivery} 段（配置即文件）。
 * 这一段与网页端「投递行为」区块一一对应，改文件或点保存都生效。
 *
 * <p><b>将来按平台覆盖策略</b>时，只需在这里加一层"按平台覆盖"，流程层与平台都不用动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryPolicyService {

    private final ConfigFileService configFileService;

    /** wait_time 没配或配得不合法时用的秒数 */
    private static final int DEFAULT_WAIT_TIME_SECONDS = 10;

    /** HR 活跃度阈值没配时的天数 */
    private static final int DEFAULT_HR_ACTIVE_MAX_DAYS = 30;

    /**
     * 取当前生效的投递策略。
     * <p>读不到配置文件时返回"保守的默认值"（不调 AI、按 30 天判 HR 活跃、间隔 10 秒），
     * 但会打警告 —— 正常情况下启动阶段 {@code syncConfigFromFile()} 已经把文件生成好了。
     */
    public DeliveryPolicy current() {
        DeliveryPolicy policy = new DeliveryPolicy();
        try {
            Map<String, Object> delivery = asMap(configFileService.read().get("delivery"));
            if (delivery.isEmpty()) {
                log.warn("config/boss.yaml 里没有 delivery 段，投递策略使用默认值");
                return policy;
            }

            policy.setFallbackGreeting(str(delivery.get("say_hi")));
            policy.setWaitSeconds(toInt(delivery.get("wait_time"), DEFAULT_WAIT_TIME_SECONDS));

            // 布尔项：只有显式写成 false/0/off 才视为关闭。
            // enable_ai / filter_dead_hr / skip_delivered_company 缺省都是"开"，
            // 与 config/boss.yaml.example 的默认值保持一致。
            policy.setAiEnabled(flag(delivery.get("enable_ai"), true));
            policy.setFilterDeadHr(flag(delivery.get("filter_dead_hr"), true));
            policy.setSkipDeliveredCompany(flag(delivery.get("skip_delivered_company"), true));
            policy.setSendImageResume(flag(delivery.get("send_img_resume"), false));
            policy.setDebug(flag(delivery.get("debugger"), false));

            policy.setHrActiveMaxDays(toInt(delivery.get("hr_active_max_days"), DEFAULT_HR_ACTIVE_MAX_DAYS));
            return policy;
        } catch (Exception e) {
            log.warn("读取投递策略失败，本次用默认值：{}", e.getMessage());
            return policy;
        }
    }

    // ------------------------------------------------------------------
    // YAML 取值小工具（BossService 里有一份同功能的私有实现；这里独立一份，
    // 避免让"平台无关的投递策略"反过来依赖平台专属的配置服务）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return java.util.Collections.emptyMap();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 布尔项：接受 true/false、1/0、on/off、yes/no；缺省用 {@code fallback} */
    private static boolean flag(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String s = value.toString().trim().toLowerCase();
        if (s.isEmpty()) {
            return fallback;
        }
        return "true".equals(s) || "1".equals(s) || "on".equals(s) || "yes".equals(s);
    }
}
