# FranHub

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 3.1.1 的二次开发版本。

- 协议：AGPL-3.0（继承上游，见 `LICENSE`）
- 基线：RikkaHub 3.1.1，锁定该版本，不跟随上游更新
- 应用名：FranHub
- 包名：`zishu.wife.Franhub`

## 与上游的差异

### 备份与恢复

备份页拆成两栏，两栏各出各的包，互不包含。

| 栏位 | 内容 | 产物 |
|---|---|---|
| 导出为文件 | 聊天记录、文件 | `rikkahub_backup_<时间>.zip` |
| 工作区与 Linux 环境 | 工作区、Linux 环境 | `franhub_workspaces_<时间>.zip` |

工作区包的内容划分：

- 工作区：只含 `/workspace` 里的文件
- Linux 环境：rootfs 里除 `/workspace` 之外的全部

工作区与环境用 tar 保存，因为 zip 存不住软链接与权限位。恢复时可选全盘替换或增量合并，全盘替换保留一份旧目录用于回滚。恢复在 App 启动、数据库初始化之前完成换入。

导出与恢复都在 ViewModel 作用域执行，切页面或退出设置页不会中断。

### 其他改动

- 单条 shell 命令超时上限从 600 秒改为 7200 秒。默认值仍是 30 秒，不传 `timeout` 的命令不受影响。
- 更新检查停用。不请求官方更新接口，也不显示更新卡片。
- 应用名从 RikkaHub 改为 FranHub，八个语言目录同步。

## 构建

需要 JDK 21、Android SDK（compileSdk 37）、NDK 28.2。

仓库里没有 `app/google-services.json`。构建前放一份占位配置即可，`package_name` 必须是 `zishu.wife.Franhub`：

```json
{
  "project_info": {
    "project_number": "123456789012",
    "project_id": "franhub-dev",
    "storage_bucket": "franhub-dev.appspot.com"
  },
  "client": [{
    "client_info": {
      "mobilesdk_app_id": "1:123456789012:android:abcdef1234567890abcdef",
      "android_client_info": { "package_name": "zishu.wife.Franhub" }
    },
    "oauth_client": [],
    "api_key": [{ "current_key": "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY0000" }],
    "services": { "appinvite_service": { "other_platform_oauth_client": [] } }
  }],
  "configuration_version": "1"
}
```

签名信息写在 `local.properties`（该文件不进版本库）：

```
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

构建：

```bash
./gradlew :app:assembleGithubRelease
```

R8 规则在 `app/proguard-franhub.pro`：保留缩减，关闭混淆与优化。关闭混淆是为了让安装包里的类名与崩溃栈保持可读。优化档在低性能机器上单次超过十分钟，构建容易被中断，所以暂时关闭。

`material3/material-color-utilities` 原本是 Git 子模块，这里改为随仓库保存的源码，克隆后无需 `submodule update` 即可编译。

## 目录

```
app/                     主模块
workspace/               工作区与 rootfs（tar 读写、包清单解析在这里）
ai/ common/ ui/ ...      上游模块
docs/franhub/            本项目的设计与变更文档
CHANGES.md               版本记录
```

## 已知限制

- 备份包不含 `app/google-services.json` 与 `local.properties`，新环境需自行准备。
- 增量合并模式失败后不保留回滚快照。
- 只有中文与英文补了新增文案，其余语言回退英文。

## 版本记录

见 [CHANGES.md](CHANGES.md)。
