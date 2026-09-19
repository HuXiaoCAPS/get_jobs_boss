package com.getjobs.worker.boss;

import com.getjobs.application.entity.BossJobDataEntity;
import com.getjobs.application.service.BossService;
import com.getjobs.worker.platform.DeliveryStore;
import com.getjobs.worker.platform.model.JobDetail;
import com.getjobs.worker.platform.model.JobPage;
import com.getjobs.worker.platform.model.JobQuery;
import com.getjobs.worker.platform.model.JobRecord;
import com.getjobs.worker.platform.model.JobStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link DeliveryStore} 的 Boss 实现：把流程层的数据操作翻译成 {@code boss_data} / 黑名单的读写。
 *
 * <p>它<b>刻意</b>放在 {@code worker.boss} 包里 —— 它是"平台侧"的东西：知道
 * {@code boss_data} 的表结构、也知道 Boss 详情接口 JSON 长什么样。
 * 流程层只认 {@link DeliveryStore} 接口，所以换平台不用动流程。
 *
 * <p>将来接第二个平台时：它会有自己的 {@code XxxDeliveryStore}；数据层要不要合并成
 * 一张带 {@code platform} 列的通表，属于阶段 4/5 的决定，不在本次范围。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BossDeliveryStore implements DeliveryStore {

    private final BossService bossService;

    // ------------------------------------------------------------------
    // 黑名单
    // ------------------------------------------------------------------

    @Override
    public Set<String> blacklist(String type) {
        try {
            return switch (type == null ? "" : type) {
                case BLACKLIST_COMPANY -> orEmpty(bossService.getBlackCompanies());
                case BLACKLIST_RECRUITER -> orEmpty(bossService.getBlackRecruiters());
                case BLACKLIST_JOB -> orEmpty(bossService.getBlackJobs());
                default -> Collections.emptySet();
            };
        } catch (Exception e) {
            log.warn("读取黑名单失败（按空处理）: type={} err={}", type, e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void addBlacklist(String type, String value) {
        bossService.addBlacklist(type, value);
    }

    // ------------------------------------------------------------------
    // 岗位记录
    // ------------------------------------------------------------------

    @Override
    public Set<String> deliveredExternalIds(String platform) {
        // 现在只有 boss 一张岗位表，platform 参数暂不参与查询
        try {
            return orEmpty(bossService.getDeliveredEncryptIds());
        } catch (Exception e) {
            log.warn("读取已投递岗位失败（按空处理）：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> deliveredCompanies(String platform) {
        // 同一家公司不重复投递：公司名下任何一个岗位投出去了，它其余岗位就都跳过
        try {
            return orEmpty(bossService.getDeliveredCompanies());
        } catch (Exception e) {
            log.warn("读取已投递公司失败（本次不做公司级去重）：{}", e.getMessage());
            return Collections.emptySet();
        }
    }

    @Override
    public void saveDiscovered(String platform, JobDetail detail, String status, String note) {
        String externalId = detail.getExternalId();
        if (externalId == null || externalId.isEmpty()) {
            return; // 没有平台内唯一 id 就没法去重，不入库
        }
        try {
            String recruiterId = detail.getRecruiterId();
            boolean exists = recruiterId != null && !recruiterId.isEmpty()
                    ? bossService.existsBossJob(externalId, recruiterId)
                    : bossService.existsBossJobByEncryptId(externalId);
            if (exists) {
                return;
            }

            BossJobDataEntity entity = new BossJobDataEntity();
            entity.setEncryptId(externalId);
            entity.setEncryptUserId(recruiterId);
            entity.setJobName(detail.getJobName());
            entity.setSalary(detail.getSalary());
            entity.setLocation(detail.getCity());
            entity.setExperience(detail.getExperience());
            entity.setDegree(detail.getDegree());
            entity.setJobDescription(detail.getJdText());
            entity.setCompanyName(detail.getCompanyName());
            entity.setHrName(detail.getHrName());
            entity.setHrPosition(detail.getHrPosition());
            entity.setHrActiveStatus(detail.getHrActiveText());
            entity.setJobUrl(detail.getDetailUrl());
            entity.setFilterNote(note);
            entity.setDeliveryStatus(status);

            // 详情 JSON 里还有一批"流程用不上、但数据页要展示"的字段（行业/规模/融资/公司介绍/地址…）。
            // 这是平台侧的解析知识，所以补在这里，而不是污染平台无关的 JobDetail 模型。
            fillFromRawJson(entity, detail.getRaw());

            bossService.insertBossJob(entity);
            log.debug("岗位入库：{} | 公司：{} | 状态：{}",
                    entity.getJobName(), entity.getCompanyName(), entity.getDeliveryStatus());
        } catch (Exception e) {
            log.warn("岗位入库失败：{}", e.getMessage());
        }
    }

    /** 从 Boss 详情接口的原始 JSON 里补齐展示字段（结构变了也只影响这里） */
    private void fillFromRawJson(BossJobDataEntity entity, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            org.json.JSONObject root = new org.json.JSONObject(raw);
            org.json.JSONObject zpData = root.optJSONObject("zpData");
            if (zpData == null) {
                return;
            }
            org.json.JSONObject jobInfo = zpData.optJSONObject("jobInfo");
            if (jobInfo != null) {
                String desc = jobInfo.optString("postDescription", null);
                if (desc != null && !desc.isEmpty()) {
                    entity.setJobDescription(desc);
                }
                entity.setRecruitmentStatus(jobInfo.optString("jobStatusDesc", null));
                entity.setCompanyAddress(jobInfo.optString("address", null));
            }
            org.json.JSONObject brand = zpData.optJSONObject("brandComInfo");
            if (brand != null) {
                entity.setIndustry(brand.optString("industryName", null));
                entity.setIntroduce(brand.optString("introduce", null));
                entity.setFinancingStage(brand.optString("stageName", null));
                entity.setCompanyScale(brand.optString("scaleName", null));
            }
        } catch (Exception e) {
            log.debug("从原始 JSON 补充岗位字段失败：{}", e.getMessage());
        }
    }

    @Override
    public void updateStatus(String platform, String externalId, String recruiterId, String status) {
        if (externalId == null || externalId.isEmpty() || recruiterId == null || recruiterId.isEmpty()) {
            log.debug("缺少 encryptId / encryptUserId，无法更新投递状态：{}", externalId);
            return;
        }
        bossService.updateDeliveryStatus(externalId, recruiterId, status);
    }

    // ------------------------------------------------------------------
    // 聊天快照
    // ------------------------------------------------------------------

    @Override
    public Map<String, String> chatSnapshot(String platform) {
        try {
            Map<String, String> snapshot = bossService.getChatSnapshot();
            return snapshot == null ? Collections.emptyMap() : snapshot;
        } catch (Exception e) {
            log.warn("读取聊天快照失败（按空处理）：{}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public void replaceChatSnapshot(String platform, Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        bossService.replaceChatSnapshot(snapshot);
    }

    // ------------------------------------------------------------------
    // 数据浏览（网页端「数据」页用）
    //
    // 这两个方法就是"数据页硬编码"的解药：页面对的是 JobRecord / JobStats 这类
    // 平台无关模型，走 /api/platforms/{id}/jobs|stats —— 换平台时页面一行都不用改。
    // Boss 这里只是把通用查询翻译成对 boss_data 的 SQL（复用 BossService 现成的方法），
    // 再把结果搬进通用模型。
    // ------------------------------------------------------------------

    @Override
    public JobPage listJobs(String platform, JobQuery query) {
        JobQuery q = query == null ? new JobQuery() : query;
        try {
            BossService.PagedResult page = bossService.listBossJobs(
                    q.statuses, q.location, q.experience, q.degree,
                    q.minK, q.maxK, q.keyword, q.safePage(), q.safeSize(), q.filterHeadhunter,
                    q.salaryUnit);
            if (page == null) {
                return JobPage.of(Collections.emptyList(), 0, q.safePage(), q.safeSize());
            }
            List<JobRecord> items = new ArrayList<>();
            if (page.items != null) {
                for (BossJobDataEntity e : page.items) {
                    items.add(toRecord(e));
                }
            }
            return JobPage.of(items, page.total, page.page, page.size);
        } catch (Exception e) {
            log.warn("查询岗位列表失败（返回空页）：{}", e.getMessage());
            return JobPage.of(Collections.emptyList(), 0, q.safePage(), q.safeSize());
        }
    }

    @Override
    public JobStats jobStats(String platform, JobQuery query) {
        JobQuery q = query == null ? new JobQuery() : query;
        JobStats out = new JobStats();
        try {
            BossService.StatsResponse resp = bossService.getBossStats(
                    q.statuses, q.location, q.experience, q.degree,
                    q.minK, q.maxK, q.keyword, q.filterHeadhunter, q.salaryUnit);
            if (resp == null) {
                return out;
            }
            if (resp.kpi != null) {
                out.total = resp.kpi.total;
                out.delivered = resp.kpi.delivered;
                out.pending = resp.kpi.pending;
                out.filtered = resp.kpi.filtered;
                out.failed = resp.kpi.failed;
                out.avgMonthlyK = resp.kpi.avgMonthlyK;
                out.avgDailyYuan = resp.kpi.avgDailyYuan;
            }
            if (resp.charts != null) {
                out.salaryBuckets = fromBuckets(resp.charts.salaryBuckets);
                out.dailySalaryBuckets = fromBuckets(resp.charts.dailySalaryBuckets);
                out.byStatus = fromNames(resp.charts.byStatus);
                out.byCity = fromNames(resp.charts.byCity);
                out.byCompany = fromNames(resp.charts.byCompany);
                out.byIndustry = fromNames(resp.charts.byIndustry);
                out.byExperience = fromNames(resp.charts.byExperience);
                out.byDegree = fromNames(resp.charts.byDegree);
                out.dailyTrend = fromNames(resp.charts.dailyTrend);
            }
        } catch (Exception e) {
            log.warn("统计岗位数据失败（返回空统计）：{}", e.getMessage());
        }
        return out;
    }

    /** {@code boss_data} 一行 → 平台无关的一条岗位记录 */
    private static JobRecord toRecord(BossJobDataEntity e) {
        JobRecord r = new JobRecord();
        r.id = e.getId();
        r.jobName = e.getJobName();
        r.companyName = e.getCompanyName();
        r.salary = e.getSalary();
        r.location = e.getLocation();
        r.experience = e.getExperience();
        r.degree = e.getDegree();
        r.hrName = e.getHrName();
        r.hrPosition = e.getHrPosition();
        r.hrActiveStatus = e.getHrActiveStatus();
        r.status = e.getDeliveryStatus();
        r.note = e.getFilterNote();
        r.jobUrl = e.getJobUrl();
        // 详情字段：列表页不展示，但"点详情"要立刻能看到，所以随列表一起带回来。
        // 体量可控（JD 几 KB，一页 20 条），省掉一次额外的往返请求。
        r.jdText = e.getJobDescription();
        r.industry = e.getIndustry();
        r.companyScale = e.getCompanyScale();
        r.financingStage = e.getFinancingStage();
        r.companyAddress = e.getCompanyAddress();
        r.recruitmentStatus = e.getRecruitmentStatus();
        r.companyIntroduce = e.getIntroduce();
        r.discoveredAt = e.getCreatedAt() == null
                ? null
                : e.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return r;
    }

    private static List<JobStats.Count> fromNames(List<BossService.NameValue> in) {
        List<JobStats.Count> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (BossService.NameValue nv : in) {
            out.add(new JobStats.Count(nv.name, nv.value));
        }
        return out;
    }

    private static List<JobStats.Count> fromBuckets(List<BossService.BucketValue> in) {
        List<JobStats.Count> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (BossService.BucketValue bv : in) {
            out.add(new JobStats.Count(bv.bucket, bv.value));
        }
        return out;
    }

    private static Set<String> orEmpty(Set<String> in) {
        return in == null ? Collections.emptySet() : in;
    }
}
