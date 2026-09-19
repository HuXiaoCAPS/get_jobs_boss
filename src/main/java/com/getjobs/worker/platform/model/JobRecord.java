package com.getjobs.worker.platform.model;

/**
 * 一条岗位记录（给网页端「数据」页看的视图）—— 平台无关。
 *
 * <p>它只描述"一个岗位长什么样"，<b>不</b>承担任何平台专属字段（Boss 的
 * {@code encrypt_id} / {@code encrypt_user_id} 之类留在 {@code boss_data} 表里，
 * 不往上带）——这样数据页就不会因为换平台而不能用。
 *
 * <p>{@code role} 之类的专有名词一律用通用说法：{@code salary} 直接给原始串
 * （Boss 上可能是 {@code 20-40K} 也可能是 {@code 200-250元/天}），怎么解释交给展示层。
 */
public class JobRecord {

    /** 表内主键（前端做列表 key 用，没有业务含义） */
    public Long id;

    /** 岗位名称 */
    public String jobName;

    /** 公司名称 */
    public String companyName;

    /** 薪资原文（如 {@code 20-40K} / {@code 200-250元/天} / {@code 面议}） */
    public String salary;

    /** 工作城市 */
    public String location;

    /** 经验要求 */
    public String experience;

    /** 学历要求 */
    public String degree;

    /** 招聘者姓名 */
    public String hrName;

    /** 招聘者职位 */
    public String hrPosition;

    /** 招聘者活跃状态原文（如 {@code 今日活跃} / {@code 3日内活跃}） */
    public String hrActiveStatus;

    /** 投递状态（取值见 {@code DeliveryStore.STATUS_*}） */
    public String status;

    /** 过滤/提示备注（被规则或黑名单挡下时的原因） */
    public String note;

    /** 岗位详情页地址 */
    public String jobUrl;

    // ------------------------------------------------------------------
    // 详情字段（列表页不展示，点「详情」才看）
    // ------------------------------------------------------------------

    /** 岗位描述（JD）正文 */
    public String jdText;

    /** 公司行业 */
    public String industry;

    /** 公司规模（如 {@code 1000-9999人}） */
    public String companyScale;

    /** 融资阶段（如 {@code 已上市} / {@code B轮}） */
    public String financingStage;

    /** 公司地址 */
    public String companyAddress;

    /** 招聘状态原文（如 {@code 招聘中}） */
    public String recruitmentStatus;

    /** 公司介绍 */
    public String companyIntroduce;

    /** 入库时间（{@code yyyy-MM-dd HH:mm}，没有就 null） */
    public String discoveredAt;
}
