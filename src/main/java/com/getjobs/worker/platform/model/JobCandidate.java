package com.getjobs.worker.platform.model;

import lombok.Data;

/**
 * 列表页里的一个「岗位候选」。
 *
 * <p>这是<b>平台无关</b>的契约模型：各平台的列表页能读到多少就填多少，
 * 读不到的留空 —— 因为有的平台列表页信息很全，有的（如 Boss）几乎什么都没有，
 * 要等 {@code openDetail} 之后才知道岗位名和公司。
 *
 * <p>流程层不依赖任何平台包，只用这个模型。
 */
@Data
public class JobCandidate {

    /** 平台标识（boss / liepin / ...），由平台实现填写 */
    private String platform;

    /**
     * 在本次搜索结果里的次序。
     * <p>平台侧用它重新定位卡片（Boss 就是 {@code cards.nth(index)}），
     * 所以它是「点开详情」的唯一必需信息。
     */
    private int index;

    /** 平台内的岗位唯一 id（Boss = encryptId）；列表页拿不到就留空，点开后再补 */
    private String externalId;

    /** 岗位名（列表页可读则填） */
    private String jobName;

    /** 公司名（列表页可读则填） */
    private String companyName;

    /** 薪资原文 */
    private String salary;

    /** 城市/区域原文（如「深圳·南山区」） */
    private String city;
}
