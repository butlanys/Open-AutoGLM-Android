package com.autoglm.android.config

object AppPackages {
    
    val APP_PACKAGES: Map<String, String> = mapOf(
        // 社交通讯
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "小红书" to "com.xingin.xhs",
        "知乎" to "com.zhihu.android",
        "豆瓣" to "com.douban.frodo",
        "飞书" to "com.ss.android.lark",
        "钉钉" to "com.alibaba.android.rimet",
        "企业微信" to "com.tencent.wework",
        
        // 电商购物
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "唯品会" to "com.achievo.vipshop",
        "得物" to "com.shizhuang.duapp",
        "闲鱼" to "com.taobao.idlefish",
        "天猫" to "com.tmall.wireless",
        "苏宁易购" to "com.suning.mobile.ebuy",
        
        // 美食外卖
        "美团" to "com.sankuai.meituan",
        "美团外卖" to "com.sankuai.meituan.takeoutnew",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",
        "肯德基" to "com.yek.android.kfc.activitys",
        "麦当劳" to "com.mcdonalds.gma.cn",
        "星巴克" to "com.starbucks.cn",
        "瑞幸咖啡" to "com.lucky.luckycoffee",
        "海底捞" to "com.haidilao.mobile",
        
        // 出行旅游
        "12306" to "com.MobileTicket",
        "携程" to "ctrip.android.view",
        "飞猪" to "com.taobao.trip",
        "去哪儿" to "com.Qunar",
        "同程旅行" to "com.tongcheng.android",
        "滴滴出行" to "com.sdu.didi.psnger",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "腾讯地图" to "com.tencent.map",
        
        // 视频娱乐
        "bilibili" to "tv.danmaku.bili",
        "爱奇艺" to "com.qiyi.video",
        "优酷" to "com.youku.phone",
        "腾讯视频" to "com.tencent.qqlive",
        "芒果TV" to "com.hunantv.imgo.activity",
        "西瓜视频" to "com.ss.android.article.video",
        "咪咕视频" to "com.cmvideo.miguvideo",
        
        // 音乐音频
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "酷狗音乐" to "com.kugou.android",
        "酷我音乐" to "cn.kuwo.player",
        "汽水音乐" to "com.luna.music",
        "喜马拉雅" to "com.ximalaya.ting.android",
        
        // 生活服务
        "支付宝" to "com.eg.android.AlipayGphone",
        "闪送" to "cn.ishansong",
        "58同城" to "com.wuba",
        "中国移动" to "com.greenpoint.android.mc10086.activity",
        "中国联通" to "com.sinovatech.unicom.ui",
        "中国电信" to "com.ct.client",
        "菜鸟" to "com.cainiao.wireless",
        "顺丰速运" to "com.sf.activity",
        
        // AI与工具
        "豆包" to "com.larus.nova",
        "WPS" to "cn.wps.moffice_eng",
        "UC浏览器" to "com.UCMobile",
        "夸克" to "com.quark.browser",
        "百度" to "com.baidu.searchbox",
        "Chrome" to "com.android.chrome",
        "Edge" to "com.microsoft.emmx",
        "Microsoft Edge" to "com.microsoft.emmx",
        "Firefox" to "org.mozilla.firefox",
        "Safari" to "com.apple.mobilesafari",
        "Opera" to "com.opera.browser",
        "Brave" to "com.brave.browser",
        
        // 阅读学习
        "微信读书" to "com.tencent.weread",
        "起点读书" to "com.qidian.QDReader",
        "得到" to "com.luojilab.player",
        "网易公开课" to "com.netease.vopen",
        
        // 系统应用
        "设置" to "com.android.settings",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d",
        "文件管理" to "com.android.documentsui",
        "日历" to "com.android.calendar",
        "时钟" to "com.android.deskclock",
        "计算器" to "com.android.calculator2",
        "电话" to "com.android.dialer",
        "短信" to "com.android.mms",
        "联系人" to "com.android.contacts"
    )
    
    fun getPackageName(appName: String): String? {
        return APP_PACKAGES[appName] 
            ?: APP_PACKAGES.entries.find { 
                it.key.equals(appName, ignoreCase = true) 
            }?.value
    }
    
    fun getAppName(packageName: String): String? {
        return APP_PACKAGES.entries.find { it.value == packageName }?.key
    }
    
    fun listSupportedApps(): List<String> {
        return APP_PACKAGES.keys.sorted()
    }
}
