package com.getjobs.worker.platform.model;

import lombok.Data;

import java.util.List;

/**
 * 一次搜索请求（关键词 × 城市），平台无关。
 *
 * <p>由 {@code JobPlatform.buildQueries()} 产出：
 * <ul>
 *   <li><b>轮换模式</b>（Boss 的默认）：每个城市各产出一个 query，{@code allowedCities} 为空；</li>
 *   <li><b>过滤模式</b>（Boss 勾选后）：只产出一个 query（{@code cityCode} 为空 = 平台自己的"不限/全国"），
 *       并把配置的城市名放进 {@code allowedCities}，交给 {@code matchesCity} 逐岗位筛。</li>
 * </ul>
 *
 * <p>为什么"轮换 vs 过滤"这种选择放在平台侧：它取决于平台能不能一次搜多个城市，
 * 而那是平台知识（Boss 的搜索一次只认一个城市码）。流程层只负责"把 query 一个个执行掉"。
 */
@Data
public class SearchQuery {

    /** 搜索关键词（必填） */
    private String keyword;

    /** 平台搜索用的城市码；为空 = 平台默认（Boss 会映射成全国码） */
    private String cityCode;

    /** 城市中文名，仅用于日志与展示 */
    private String cityName;

    /** 过滤模式下的"允许城市"名单（岗位城市命中其一才投）；为空 = 不筛 */
    private List<String> allowedCities;

    public SearchQuery() {
    }

    public SearchQuery(String keyword, String cityCode, String cityName, List<String> allowedCities) {
        this.keyword = keyword;
        this.cityCode = cityCode;
        this.cityName = cityName;
        this.allowedCities = allowedCities;
    }

    /** 日志用：关键词 + 城市（有城市时） */
    public String describe() {
        if (cityName == null || cityName.isEmpty()) {
            return keyword;
        }
        return keyword + "@" + cityName;
    }
}
