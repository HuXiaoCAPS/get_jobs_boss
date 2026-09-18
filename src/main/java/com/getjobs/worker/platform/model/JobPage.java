package com.getjobs.worker.platform.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位列表的一页 —— 平台无关。
 *
 * <p>{@code DeliveryStore#listJobs} 的返回类型。分页在<b>平台侧</b>做：
 * 各平台的数据源不同（Boss 是 SQL、假平台是内存），能怎么高效分页只有它自己知道。
 */
public class JobPage {

    /** 本页数据 */
    public List<JobRecord> items = new ArrayList<>();

    /** 符合筛选条件的总条数（不是本页条数） */
    public long total;

    /** 当前页码（从 1 开始） */
    public int page = 1;

    /** 每页条数 */
    public int size = 20;

    public static JobPage of(List<JobRecord> items, long total, int page, int size) {
        JobPage p = new JobPage();
        p.items = items == null ? new ArrayList<>() : items;
        p.total = total;
        p.page = page;
        p.size = size;
        return p;
    }

    /** 总页数（至少 1，便于前端显示"第 x / y 页"） */
    public int totalPages() {
        int s = size <= 0 ? 20 : size;
        return Math.max(1, (int) Math.ceil((double) total / s));
    }
}
