package com.github.tvbox.osc.update;

/**
 * 在线更新相关常量（规格1：稳定可靠的自动更新 + 多镜像 + 后台下载）
 * 更新清单与安装包均托管在 LDW-Cinema-Next 仓库的 app-latest Release。
 */
public final class UpdateConstants {
    private UpdateConstants() {}

    /** 多镜像更新清单地址（按优先级排列，前面失败自动切后面） */
    public static final String[] MANIFEST_URLS = new String[]{
            "https://ghproxy.net/https://github.com/lidawei1985/LDW-Cinema-Next/releases/download/app-latest/update-mobile.json",
            "https://cdn.jsdelivr.net/gh/lidawei1985/LDW-Cinema-Next@app-latest/update-mobile.json",
            "https://mirror.ghproxy.com/https://github.com/lidawei1985/LDW-Cinema-Next/releases/download/app-latest/update-mobile.json",
            "https://github.com/lidawei1985/LDW-Cinema-Next/releases/download/app-latest/update-mobile.json"
    };

    /** HTTP 连接/读取超时（毫秒）：短超时 + 多镜像切换，避免几KB级慢下载卡死 */
    public static final int CONNECT_TIMEOUT_MS = 8000;
    public static final int READ_TIMEOUT_MS = 15000;

    /** 本地存储键名 */
    public static final String SP_NAME = "ldw_update";
}
