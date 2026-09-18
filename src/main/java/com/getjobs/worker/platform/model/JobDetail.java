package com.getjobs.worker.platform.model;

import lombok.Data;

/**
 * 点开之后拿到的「岗位详情」——平台无关的契约模型。
 *
 * <p>{@code jdText} 是<b>给规则与 AI 用</b>的文本，平台负责把该拼的都拼好
 * （Boss 的实现 = 岗位名 + JD 正文 + showSkills 标签，见 {@code Boss.buildRulesText}）。
 * 这样流程层永远不需要知道"这个平台的关键信息藏在哪几个字段里"。
 */
@Data
public class JobDetail {

    /** 平台标识 */
    private String platform;

    /** 对应候选在列表里的次序（发招呼语时要回到那张卡片） */
    private int index;

    /** 平台内的岗位唯一 id（Boss = encryptId），用于去重与状态更新 */
    private String externalId;

    /** 招聘者 id（Boss = encryptUserId）；平台不用则留空 */
    private String recruiterId;

    /** 岗位详情页地址（可空） */
    private String detailUrl;

    private String jobName;
    private String companyName;
    private String salary;
    private String city;
    private String experience;
    private String degree;

    private String hrName;
    private String hrPosition;

    /** HR 活跃描述原文（如「本周活跃」），用于活跃度过滤 */
    private String hrActiveText;

    /** 给规则/AI 用的正文文本 */
    private String jdText;

    /** 平台原始 JSON / 原始文本（诊断与排查用，可空） */
    private String raw;

    /**
     * 详情是否真的解析出来了。
     * <p>Boss 上"点了卡片但详情接口没回来"很常见，这时必须跳过而不是盲投，
     * 所以流程层需要一个明确的信号（老代码是靠 {@code jobName} 是否为空来判的）。
     */
    private boolean parsed;

    /** 供日志用的简短描述 */
    public String describe() {
        String job = jobName == null || jobName.isEmpty() ? "(无岗位名)" : jobName;
        String company = companyName == null || companyName.isEmpty() ? "" : companyName;
        return company.isEmpty() ? job : company + " / " + job;
    }
}
