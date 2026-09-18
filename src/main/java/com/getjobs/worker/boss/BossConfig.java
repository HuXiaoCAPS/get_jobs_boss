package com.getjobs.worker.boss;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 *
 * Boss配置数据类
 * 配置加载由 BossConfigLoaderService 负责
 */
@Data
public class BossConfig {
    /**
     * 用于打招呼的语句
     */
    private String sayHi;

    /**
     * 开发者模式
     */
    private Boolean debugger;

    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private List<String> cityCode;

    /**
     * 自定义城市编码映射
     */
    private Map<String, String> customCityCode;

    /**
     * 多城市处理方式：true = 过滤模式（搜索时用全国码 100010000，再按岗位自身的城市筛选）；
     * false / null = 轮换模式（对每个城市各搜一轮，即原来的行为）
     */
    private Boolean cityFilterMode;

    /**
     * 城市中文名列表（如 [深圳, 广州]）。
     * 过滤模式用它比对岗位详情里的 locationName；列表里只有「不限」时不过滤。
     */
    private List<String> cityNames;

    /**
     * 排除的城市/省份（如 [广东, 东莞]）。命中即跳过 —— 两种城市模式下都生效。
     * 省份名会由 {@code CityFilter} 展开成该省城市，省得逐个枚举。
     */
    private List<String> cityExclude;

    /** 同一家公司不重复投递（null/true = 开启） */
    private Boolean skipDeliveredCompany;

    /**
     * 行业列表
     */
    private List<String> industry;

    /**
     * 工作经验要求
     */
    private List<String> experience;

    /**
     * 工作类型
     */
    private String jobType;

    /**
     * 薪资范围（多选）
     */
    private java.util.List<String> salary;

    /**
     * 学历要求列表
     */
    private List<String> degree;

    /**
     * 公司规模列表
     */
    private List<String> scale;

    /**
     * 公司融资阶段列表
     */
    private List<String> stage;

    /**
     * 是否开放AI检测
     */
    private Boolean enableAI;

    /**
     * 是否过滤不活跃hr
     */
    private Boolean filterDeadHR;

    /**
     * HR 活跃度阈值（天）：把活跃描述（如「5月内活跃」）解析成天数，
     * 超过该天数就视为不活跃、跳过投递。
     * 0 = 不启用细粒度判定（退回旧的「含年」判定）；null 时按 30 天处理。
     */
    private Integer hrActiveMaxDays;

    /**
     * 是否发送图片简历
     */
    private Boolean sendImgResume;

    /**
     * 目标薪资
     */
    private List<Integer> expectedSalary;

    /**
     * 等待时间
     */
    private String waitTime;

    /**
     * HR未上线状态
     */
    private List<String> deadStatus;
}
