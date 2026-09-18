package com.getjobs.worker.platform.fake;

import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.JobPlatform;
import com.getjobs.worker.platform.model.ChatReply;
import com.getjobs.worker.platform.model.DeliveryPolicy;
import com.getjobs.worker.platform.model.JobCandidate;
import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.SearchQuery;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个<b>假装出来的平台</b>：纯内存、不碰浏览器、不发网络请求。
 *
 * <p><b>它存在的意义不是"能投递"，而是"验收插件式"</b>：
 * 如果这个从零写的平台能让 {@code DeliveryRunner} 跑完一整轮
 * （搜索 → 详情 → 过滤 → 投递 → 落库 → 限速），并且 runner 一行都没为它改过，
 * 那就证明"加平台不需要动流程层"；反过来删掉任何真实平台也不影响流程层。
 *
 * <p>六个假岗位刻意覆盖了每条分支，跑一次就能看出流程是否按预期工作：
 * <ol>
 *   <li>正常岗位 → 投递成功（已投递）</li>
 *   <li>JD 命中 reject 规则 → 已过滤</li>
 *   <li>公司命中黑名单 → 已过滤</li>
 *   <li>HR 活跃"一年前" → 已过滤</li>
 *   <li>详情没解析出来 → 跳过（不投、不入库）</li>
 *   <li>发送失败 → 投递失败</li>
 * </ol>
 * 另外它还会吐出一条"明确拒绝"的 HR 回复，用来验证自动拉黑。
 */
public class FakePlatform implements JobPlatform {

    public static final String ID = "fake";

    private final List<JobDetail> details = new ArrayList<>();

    /** 数据实现由调用方传进来（验收入口自己 new 一个内存实现，才能读回过程记录） */
    private final InMemoryDeliveryStore store;

    public FakePlatform(InMemoryDeliveryStore store) {
        this.store = store;
        // 0) 正常岗位
        details.add(detail(0, "f-1", "r-1", "数据开发工程师", "正常公司", "20-30K", "深圳·南山区",
                "本周活跃", jd("数据开发", "Hive Spark Flink"), true));
        // 1) 岗位名命中黑名单（InMemoryDeliveryStore 预置了「数仓开发」；刻意不依赖
        //    jd-rules.txt，这样验收结果与机器上的规则文件内容无关）
        details.add(detail(1, "f-2", "r-2", "数仓开发", "高门槛公司", "25-40K", "深圳·福田区",
                "本周活跃", jd("数据仓库", "仅研究生 需 3 年以上"), true));
        // 2) 公司黑名单命中
        details.add(detail(2, "f-3", "r-3", "大数据开发", "黑名单公司", "18-25K", "广州·天河区",
                "本月活跃", jd("大数据", "Hive Spark"), true));
        // 3) HR 一年多没活跃（阈值 30 天）
        details.add(detail(3, "f-4", "r-4", "ETL 工程师", "沉默公司", "15-22K", "广州·越秀区",
                "一年前活跃", jd("ETL", "Hive Spark"), true));
        // 4) 详情没解析出来
        JobDetail broken = new JobDetail();
        broken.setPlatform(ID);
        broken.setIndex(4);
        broken.setParsed(false);
        details.add(broken);
        // 5) 正常岗位但发送失败
        details.add(detail(5, "f-6", "r-6", "数据平台开发", "发送失败公司", "22-32K", "深圳·宝安区",
                "刚刚活跃", jd("数据平台", "Java Hive Spark"), true));
    }

    private static JobDetail detail(int index, String externalId, String recruiterId, String jobName,
                                    String company, String salary, String city, String hrActive,
                                    String jdText, boolean parsed) {
        JobDetail d = new JobDetail();
        d.setPlatform(ID);
        d.setIndex(index);
        d.setExternalId(externalId);
        d.setRecruiterId(recruiterId);
        d.setJobName(jobName);
        d.setCompanyName(company);
        d.setSalary(salary);
        d.setCity(city);
        d.setExperience("3-5年");
        d.setDegree("本科");
        d.setHrName("假 HR");
        d.setHrPosition("技术负责人");
        d.setHrActiveText(hrActive);
        d.setJdText(jdText);
        d.setDetailUrl("https://example.invalid/job/" + externalId);
        d.setParsed(parsed);
        return d;
    }

    /** 拼成"岗位名 + 正文 + 标签"的形态，和真平台保持一致 */
    private static String jd(String jobName, String body) {
        return jobName + " " + body;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "假平台（验收用）";
    }

    @Override
    public boolean isLoggedIn() {
        return true;
    }

    @Override
    public void ensureSession() {
        // 内存实现，没有会话要建立
    }

    @Override
    public List<SearchQuery> buildQueries() {
        return List.of(new SearchQuery("假关键词", "", "全国", null));
    }

    @Override
    public List<JobCandidate> search(SearchQuery query) {
        List<JobCandidate> candidates = new ArrayList<>();
        for (JobDetail d : details) {
            JobCandidate c = new JobCandidate();
            c.setPlatform(ID);
            c.setIndex(d.getIndex());
            candidates.add(c);
        }
        return candidates;
    }

    @Override
    public JobDetail openDetail(JobCandidate candidate) {
        for (JobDetail d : details) {
            if (d.getIndex() == candidate.getIndex()) {
                return d;
            }
        }
        JobDetail empty = new JobDetail();
        empty.setPlatform(ID);
        empty.setIndex(candidate.getIndex());
        empty.setParsed(false);
        return empty;
    }

    @Override
    public boolean matchesCity(JobDetail detail) {
        return true;
    }

    /** 只有 index=0 的岗位能"发出去"，index=5 用来验证「投递失败」分支 */
    @Override
    public boolean sendGreeting(JobDetail detail, String message) {
        return detail.getIndex() == 0;
    }

    /** 吐一条"明确拒绝"的回复，验证自动拉黑 */
    @Override
    public List<ChatReply> scanReplies() {
        return List.of(new ChatReply("拒绝我的公司", "很遗憾，不合适"));
    }

    /** 内存实现：绝不会碰到真实数据库 */
    @Override
    public DeliveryStore store() {
        return store;
    }

    /**
     * 验收用的快速策略 —— 投递策略已经不属于平台（由全局 DeliveryPolicyService 提供），
     * 这里只提供一个静态方法给验收入口手动使用：不间隔、不调 AI。
     */
    public static DeliveryPolicy fastPolicyForVerification() {
        DeliveryPolicy policy = new DeliveryPolicy();
        policy.setFallbackGreeting("你好，这是我的假招呼语");
        policy.setDebug(false);
        policy.setWaitSeconds(1);   // 验收要快
        policy.setAiEnabled(false); // 别去真的请求模型
        policy.setHrActiveMaxDays(30);
        return policy;
    }
}
