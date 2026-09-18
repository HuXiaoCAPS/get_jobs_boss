package com.getjobs.worker.platform;

import com.getjobs.worker.platform.model.ChatReply;
import com.getjobs.worker.platform.model.JobCandidate;
import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.SearchQuery;

import java.util.List;

/**
 * 一个「求职平台」要提供的能力 = 平台与程序之间唯一的契约。
 *
 * <p><b>设计原则</b>
 * <ol>
 *   <li><b>只暴露「做什么」，不暴露「怎么做」</b>：方法签名里不出现 Locator / Page / JSON / 平台配置类，
 *       平台内部用什么实现（Playwright、HTTP API、甚至内存假数据）与流程层无关。</li>
 *   <li><b>线程</b>：实现方所有方法都要在 Playwright 专用线程上被调用（流程层会用
 *       {@code PlaywrightManager.callOnPlaywright} 包起来）。因此接口里<b>不能有回调式 API</b>
 *       （如 onResponse / listener），否则会跨线程操作页面，随机抛
 *       "Object doesn't exist: request@/response@"。</li>
 *   <li><b>自给自足</b>：平台自己负责读自己的配置（{@link #buildQueries()} 不带配置参数），
 *       流程层因此不需要认识任何平台的配置类型 —— 这是"删掉某个平台仍能运行"的前提。</li>
 * </ol>
 *
 * <p><b>实现方</b>：加一个平台 = 新增一个实现类 + 打上 Spring 的 {@code @Component}，
 * 被 {@link PlatformRegistry} 自动发现，不需要改流程层、不需要改注册表、不需要改前端。
 */
public interface JobPlatform {

    /** 平台标识，全小写，用于落库 / 接口 / 前端路由（如 {@code boss}） */
    String id();

    /** 展示名（如 {@code Boss直聘}） */
    String displayName();

    // ------------------------------------------------------------------
    // 会话与登录
    // ------------------------------------------------------------------

    /** 当前是否已登录（只读探测，不能阻塞太久） */
    boolean isLoggedIn();

    /**
     * 确保会话可用：打开平台页面、必要时引导登录（扫码）。
     * <p>在投递开始时调用一次；已登录时应快速返回。
     */
    void ensureSession();

    /** 暂停后台登录监控（投递期间避免与业务流程并发操作同一个页面） */
    default void pauseMonitoring() {
    }

    /** 恢复后台登录监控 */
    default void resumeMonitoring() {
    }

    /**
     * 在「本平台要求的工作线程」上执行任务。
     *
     * <p>默认就在当前线程执行；<b>Playwright 类的平台必须覆盖它</b>，
     * 改为把任务丢到 Playwright 专用线程上（Boss 是
     * {@code playwrightManager.runOnPlaywright(task)}）——
     * Playwright 的对象只能在创建它的线程上使用，否则会随机抛
     * {@code Object doesn't exist: request@/response@}。
     *
     * <p>之所以把它做成平台方法而不是写死在任务壳里：线程约束是<b>平台知识</b>，
     * 任务壳（{@code PlatformTaskManager}）不该知道哪个平台用浏览器、哪个平台直接用 HTTP。
     */
    default void runTask(Runnable task) {
        task.run();
    }

    // ------------------------------------------------------------------
    // 搜索
    // ------------------------------------------------------------------

    /**
     * 把平台配置翻译成"要执行的搜索"。
     * <p>"几个关键词 × 几个城市"以及"多城市是轮换搜索还是全国搜索+过滤"这类取舍属于平台知识，
     * 由平台决定（见 {@link SearchQuery} 的说明）。
     */
    List<SearchQuery> buildQueries();

    /**
     * 执行一次搜索，返回列表页里的全部候选（平台内部负责翻页/滚动加载）。
     * <p>返回空列表表示"这个关键词没有结果"，不是错误。
     */
    List<JobCandidate> search(SearchQuery query);

    // ------------------------------------------------------------------
    // 详情与投递
    // ------------------------------------------------------------------

    /**
     * 打开某个候选的详情并解析成模型。
     * <p>解析失败时返回 {@code parsed=false} 的对象（而不是 null）——流程层据此跳过该岗位。
     */
    JobDetail openDetail(JobCandidate candidate);

    /**
     * 该岗位是否符合平台层面的筛选条件（Boss 用它做"多城市过滤"）。
     * <p>平台无关的过滤（JD 规则、黑名单、HR 活跃度）不在这里，那些由流程层统一做。
     */
    default boolean matchesCity(JobDetail detail) {
        return true;
    }

    /**
     * 发招呼语。
     * <p>平台内部负责"进入岗位详情页 → 打开沟通窗口 → 输入 → 发送 → 收尾"。
     *
     * @return true = 消息确实发出去了（流程层据此记「已投递」）；
     *         false = 没发出去（找不到按钮、输入框没出现……），流程层记「投递失败」。
     *         <b>不要抛异常</b>打断整轮投递，失败就打日志 + 返回 false。
     */
    boolean sendGreeting(JobDetail detail, String message);

    /** 是否发送图片简历（平台能力，Boss 需要 resume.jpg 资源） */
    default boolean supportsImageResume() {
        return false;
    }

    // ------------------------------------------------------------------
    // 聊天页
    // ------------------------------------------------------------------

    /**
     * 扫描聊天页，返回当前所有会话（公司名 + 最新消息）。
     * <p>"跟上次快照比、哪些是新回复"由流程层判断；不支持聊天页的平台返回空列表即可。
     */
    default List<ChatReply> scanReplies() {
        return List.of();
    }

    // ------------------------------------------------------------------
    // 数据实现
    // ------------------------------------------------------------------

    /**
     * 本平台的数据实现 —— "数据落在哪张表"同样是平台知识：
     * Boss 返回 {@code BossDeliveryStore}（写 {@code boss_data}），假平台返回内存实现。
     *
     * <p>流程层每轮都从这里取一次，所以用一个平台自己的、绝不会写错库的实现就行
     * （也正因为这样，"用假平台试跑"不可能污染真实数据库）。
     *
     * <p><b>注意</b>：投递策略（间隔 / AI 开关 / HR 阈值 / 兜底招呼语…）<b>不在这里</b> ——
     * 那些与平台无关，由 {@code DeliveryPolicyService} 统一提供。
     */
    DeliveryStore store();
}
