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

## 已知限制

- 备份包不含 `app/google-services.json` 与 `local.properties`，新环境需自行准备。
- 增量合并模式失败后不保留回滚快照。
- 只有中文与英文补了新增文案，其余语言回退英文。

## 版本记录
V0.1.0 新增备份和恢复Linux环境和Workspace功能。
V0.2.0 将shell执行时间从600s强制限制改为7200s(暂时解决措施)
详见 [CHANGES.md](CHANGES.md)。

### 说在最后
- 本项目基本所有工作均由LLM搭配名为RikkaHub的Android Light Agent完成。LLM为Deepseek-v4-flash。
- 感谢RikkaHub作者@re-ove，感谢995fuviokd-crypto（即使他没有遵循AGPL协议）

Human vision. AI precision
