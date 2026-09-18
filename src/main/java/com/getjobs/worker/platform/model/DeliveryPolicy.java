package com.getjobs.worker.platform.model;

import lombok.Data;

/**
 * 投递策略 —— 「怎么投」的规则，平台无关。
 *
 * <p>这些规则<b>不由平台决定</b>：它们属于"这轮投递怎么跑"，与用哪个网站招人无关。
 * 所以它们不在 {@link com.getjobs.worker.platform.JobPlatform} 接口上，
 * 而是由全局的 {@code application.service.DeliveryPolicyService} 从
 * {@code config/boss.yaml} 的 {@code delivery} 段读出来交给流程层。
 */
@Data
public class DeliveryPolicy {

    /** AI 没启用 / 生成失败时的兜底招呼语；为空会导致投递失败 */
    private String fallbackGreeting;

    /** 调试模式：只遍历岗位、不真的投递 */
    private boolean debug;

    /** 每个岗位之间的停顿秒数（太小容易触发风控） */
    private int waitSeconds = 10;

    /** 是否用 AI 生成招呼语 */
    private boolean aiEnabled;

    /**
     * 是否按 HR 活跃度过滤（配置里的 filter_dead_hr）。
     * false = 完全不看活跃度；true 时再由 {@link #hrActiveMaxDays} 定阈值。
     */
    private boolean filterDeadHr = true;

    /** HR 活跃度阈值（天）：超过就不投；null 按 30 天，0 关闭细粒度判定 */
    private Integer hrActiveMaxDays;

    /** 是否发送图片简历（平台还要支持才行，见 JobPlatform#supportsImageResume） */
    private boolean sendImageResume;

    /**
     * 同一家公司不重复投递：公司名下任何一个岗位投过之后，它其余岗位一律跳过。
     * 默认开启（同一家公司常挂多个相近岗位，逐个岗位去重挡不住重复打扰）。
     */
    private boolean skipDeliveredCompany = true;
}
