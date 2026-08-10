package com.github.tvbox.osc.update;

/** 更新清单解析结果模型 */
public class UpdateInfo {
    public int versionCode;
    public String versionName;
    public String[] apkUrls;
    public String sha256;
    public String changelog;
    public boolean force;

    public boolean isValid() {
        return versionCode > 0 && apkUrls != null && apkUrls.length > 0;
    }
}
