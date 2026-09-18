package com.getjobs.worker.platform;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 城市 / 省份的排除判定 —— 平台无关的中文地名知识。
 *
 * <p><b>为什么需要它</b>：岗位详情里的地点是「城市·区」（如「深圳·南山区」），
 * 里面<b>没有省份</b>。所以想"整个广东都不投"时，逐个枚举广东的城市太繁琐，
 * 这里就内置一份「省份 → 城市」映射：填「广东」自动展开成广州/深圳/东莞…，
 * 再用"地名字符串包含"去匹配岗位地点。
 *
 * <p><b>匹配语义</b>：包含匹配（fail-open 的反面）——只有地点里真的出现了排除词（或它的展开城市）
 * 才跳过；地点取不到、或排除表为空时一律放行，绝不让"配置没写对"变成"一个岗位都不投"。
 *
 * <p>写在这里而不是 Boss 包里：中文省份与城市的对应关系与具体平台无关，
 * 任何平台只要给出"城市·区"这种地点文本都能复用。
 */
public final class CityFilter {

    private CityFilter() {
    }

    /**
     * 省份 / 直辖市 → 该地主要城市。
     * <p>刻意做成紧凑的字符串表（空格分隔）而不是一堆 List.of，便于增补；
     * 只收录 Boss 上常见的城市，缺了不影响正确性 —— 没展开到的城市名仍然可以单独写进排除表。
     */
    private static final Map<String, String> PROVINCE_CITIES = new LinkedHashMap<>();

    static {
        PROVINCE_CITIES.put("广东", "广州 深圳 东莞 佛山 珠海 中山 惠州 汕头 江门 湛江 肇庆 茂名 揭阳 梅州 清远 韶关 潮州 阳江 河源 云浮 汕尾");
        PROVINCE_CITIES.put("江苏", "南京 苏州 无锡 常州 南通 徐州 扬州 盐城 泰州 镇江 淮安 连云港 宿迁");
        PROVINCE_CITIES.put("浙江", "杭州 宁波 温州 嘉兴 湖州 绍兴 金华 衢州 舟山 台州 丽水");
        PROVINCE_CITIES.put("山东", "济南 青岛 烟台 潍坊 淄博 临沂 济宁 泰安 威海 日照 东营 聊城 德州 滨州 菏泽 枣庄");
        PROVINCE_CITIES.put("四川", "成都 绵阳 德阳 南充 宜宾 泸州 自贡 乐山 内江 达州 遂宁 眉山 广元 雅安 攀枝花");
        PROVINCE_CITIES.put("湖北", "武汉 宜昌 襄阳 荆州 黄石 十堰 荆门 孝感 黄冈 咸宁 随州 鄂州");
        PROVINCE_CITIES.put("湖南", "长沙 株洲 湘潭 衡阳 岳阳 常德 郴州 邵阳 益阳 永州 怀化 娄底 张家界");
        PROVINCE_CITIES.put("河南", "郑州 洛阳 开封 新乡 许昌 焦作 平顶山 安阳 南阳 信阳 商丘 周口 驻马店 濮阳 漯河 三门峡 鹤壁");
        PROVINCE_CITIES.put("河北", "石家庄 唐山 保定 廊坊 邯郸 邢台 沧州 承德 张家口 衡水 秦皇岛");
        PROVINCE_CITIES.put("福建", "福州 厦门 泉州 漳州 莆田 三明 南平 龙岩 宁德");
        PROVINCE_CITIES.put("安徽", "合肥 芜湖 蚌埠 淮南 马鞍山 安庆 滁州 阜阳 宿州 六安 亳州 宣城 池州 铜陵 黄山 淮北");
        PROVINCE_CITIES.put("陕西", "西安 宝鸡 咸阳 渭南 延安 榆林 汉中 安康 商洛 铜川");
        PROVINCE_CITIES.put("辽宁", "沈阳 大连 鞍山 抚顺 本溪 丹东 锦州 营口 阜新 辽阳 盘锦 铁岭 朝阳 葫芦岛");
        PROVINCE_CITIES.put("吉林", "长春 吉林 四平 辽源 通化 白山 松原 白城 延边");
        PROVINCE_CITIES.put("黑龙江", "哈尔滨 齐齐哈尔 牡丹江 佳木斯 大庆 鸡西 双鸭山 伊春 七台河 鹤岗 黑河 绥化");
        PROVINCE_CITIES.put("山西", "太原 大同 阳泉 长治 晋城 朔州 晋中 运城 忻州 临汾 吕梁");
        PROVINCE_CITIES.put("江西", "南昌 九江 赣州 吉安 上饶 宜春 抚州 萍乡 新余 鹰潭 景德镇");
        PROVINCE_CITIES.put("广西", "南宁 柳州 桂林 梧州 北海 防城港 钦州 贵港 玉林 百色 贺州 河池 来宾 崇左");
        PROVINCE_CITIES.put("云南", "昆明 曲靖 玉溪 保山 昭通 丽江 普洱 临沧 大理 红河");
        PROVINCE_CITIES.put("贵州", "贵阳 遵义 六盘水 安顺 毕节 铜仁 黔东南 黔南 黔西南");
        PROVINCE_CITIES.put("甘肃", "兰州 嘉峪关 金昌 白银 天水 武威 张掖 平凉 酒泉 庆阳 定西 陇南");
        PROVINCE_CITIES.put("内蒙古", "呼和浩特 包头 乌海 赤峰 通辽 鄂尔多斯 呼伦贝尔 巴彦淖尔 乌兰察布");
        PROVINCE_CITIES.put("新疆", "乌鲁木齐 克拉玛依 吐鲁番 哈密 昌吉");
        PROVINCE_CITIES.put("宁夏", "银川 石嘴山 吴忠 固原 中卫");
        PROVINCE_CITIES.put("青海", "西宁 海东");
        PROVINCE_CITIES.put("西藏", "拉萨 日喀则");
        PROVINCE_CITIES.put("海南", "海口 三亚 三沙 儋州");
    }

    /** 省份全称 → 简称，便于用户写「广东省」「广西壮族自治区」这种全称 */
    private static final Map<String, String> PROVINCE_ALIASES = new LinkedHashMap<>();

    static {
        PROVINCE_ALIASES.put("广东省", "广东");
        PROVINCE_ALIASES.put("江苏省", "江苏");
        PROVINCE_ALIASES.put("浙江省", "浙江");
        PROVINCE_ALIASES.put("山东省", "山东");
        PROVINCE_ALIASES.put("四川省", "四川");
        PROVINCE_ALIASES.put("湖北省", "湖北");
        PROVINCE_ALIASES.put("湖南省", "湖南");
        PROVINCE_ALIASES.put("河南省", "河南");
        PROVINCE_ALIASES.put("河北省", "河北");
        PROVINCE_ALIASES.put("福建省", "福建");
        PROVINCE_ALIASES.put("安徽省", "安徽");
        PROVINCE_ALIASES.put("陕西省", "陕西");
        PROVINCE_ALIASES.put("辽宁省", "辽宁");
        PROVINCE_ALIASES.put("吉林省", "吉林");
        PROVINCE_ALIASES.put("黑龙江省", "黑龙江");
        PROVINCE_ALIASES.put("山西省", "山西");
        PROVINCE_ALIASES.put("江西省", "江西");
        PROVINCE_ALIASES.put("广西壮族自治区", "广西");
        PROVINCE_ALIASES.put("广西省", "广西");
        PROVINCE_ALIASES.put("云南省", "云南");
        PROVINCE_ALIASES.put("贵州省", "贵州");
        PROVINCE_ALIASES.put("甘肃省", "甘肃");
        PROVINCE_ALIASES.put("内蒙古自治区", "内蒙古");
        PROVINCE_ALIASES.put("新疆维吾尔自治区", "新疆");
        PROVINCE_ALIASES.put("宁夏回族自治区", "宁夏");
        PROVINCE_ALIASES.put("青海省", "青海");
        PROVINCE_ALIASES.put("西藏自治区", "西藏");
        PROVINCE_ALIASES.put("海南省", "海南");
    }

    /**
     * 把用户填的排除项展开成"匹配关键词"集合：
     * 省份（含全称）→ 该省城市们；其它项原样保留（城市名、区名都行）。
     *
     * @param excludes 用户填的排除项（如 [广东, 东莞, 上海]）；null/空返回空集合
     */
    public static Set<String> expand(List<String> excludes) {
        if (excludes == null || excludes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String raw : excludes) {
            if (raw == null) {
                continue;
            }
            // 去掉「省」「市」「自治区」这类后缀后再判断，用户怎么写都能命中
            String item = raw.trim()
                    .replace("自治区", "")
                    .replace("壮族", "")
                    .replace("回族", "")
                    .replace("维吾尔", "")
                    .trim();
            if (item.isEmpty()) {
                continue;
            }

            String province = PROVINCE_CITIES.containsKey(item) ? item : PROVINCE_ALIASES.get(item);
            if (province == null && PROVINCE_CITIES.containsKey(item + "省")) {
                province = item + "省";
            }
            if (province != null) {
                String cities = PROVINCE_CITIES.get(province);
                if (cities != null) {
                    for (String city : cities.split("\\s+")) {
                        if (!city.isBlank()) {
                            keywords.add(city);
                        }
                    }
                }
                // 省份名本身也留着：有些平台的地点会写成「广东·深圳」
                keywords.add(province);
                continue;
            }
            // 不是省份名，就当作城市/区名直接用
            keywords.add(item);
        }
        return keywords;
    }

    /**
     * 岗位地点是否命中排除表。
     *
     * @param locationName 岗位地点原文（如「深圳·南山区」）；为空时返回 false（放行）
     * @param keywords     {@link #expand(List)} 的结果
     */
    public static boolean isExcluded(String locationName, Set<String> keywords) {
        if (locationName == null || locationName.isEmpty() || keywords == null || keywords.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (locationName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
