# Vault（知识库）备份

**为什么需要**：Obsidian Vault 是全项目知识的**唯一真源**（STATE / TASKS / PROJECT / LOG / Decisions / Wiki）。
它当前只有本地目录 + 本地 git，**没有远端**——磁盘损坏、目录被误删、勒索软件，都是全损。
本页给出可执行的备份方式与**已验证过的恢复方法**。

---

## 1. 一键备份

```powershell
pwsh scripts/backup-vault.ps1                              # 默认目标 D:\Backups\guarantee-ai-admin-vault
pwsh scripts/backup-vault.ps1 -Dest E:\vault-bak           # 指定目标（建议指向**另一块盘**或云同步目录）
pwsh scripts/backup-vault.ps1 -Push                        # 同时推送到 Vault 已配置的远端（若有）
pwsh scripts/backup-vault.ps1 -Keep 20 -NoCommit           # 保留 20 份 / 不自动提交
```

脚本做七件事（顺序固定）：

1. **校验身份**：读 `.agent/vault.local.yaml` 的 `vault.path`，再核对 Vault 的 `VAULT_ID.md` 里的 `project_id`
   是否与本仓 `.agent/project.yaml` 一致——**不一致直接拒绝**（避免备份到别的库）；
2. 提交 Vault 里未提交的改动（`-NoCommit` 可跳过）；
3. 生成**全量历史 bundle**：`git bundle create vault-<时间戳>.bundle --all`；
4. 生成**工作区快照 zip**（排除 `.git`，便于人工翻看）；
5. `git bundle verify` 校验 bundle 可用；
6. 轮转保留最近 `-Keep` 份，并写 `MANIFEST.md`（时间 / HEAD / 大小 / 文件数）；
7. 可选推送远端。

**产出**（首次执行，2026-10-01 02:03）：

```
D:\Backups\guarantee-ai-admin-vault\
  MANIFEST.md                        # 清单与恢复说明
  vault-20261001-020306.bundle       # 0.20 MB（完整历史）
  vault-20261001-020306.zip          # 3.74 MB（工作区快照，108 个文件）
```

---

## 2. 恢复（已实测）

```powershell
# 方式 A：从 bundle 恢复完整历史
git clone D:\Backups\guarantee-ai-admin-vault\vault-20261001-020306.bundle <目标目录>

# 方式 B：只要工作区快照
Expand-Archive D:\Backups\guarantee-ai-admin-vault\vault-20261001-020306.zip <目标目录>
```

**演练记录（2026-10-01）**：`git clone` 上述 bundle 到一个临时目录 → 历史完整（HEAD `50d0ff1`
「docs(vault): T6 收口轮全部完成…」）、108 个文件、`04_Work/Active/guarantee-ai-admin/STATE.md` 与 `LOG.md` 均在。
**即备份链路可恢复，不是"看起来有文件"。**

---

## 3. 配一个远端（推荐；当前 `gh` CLI 未安装，故需手工三步）

本轮核对环境时发现：本机**没有安装 GitHub CLI**，`gh` 不可用，所以无法由 Agent 直接建私有仓库；Vault 也尚未配置任何远端。
手工三步（在 Vault 目录执行）：

```powershell
cd D:\Users\12209\Documents\guarantee-ai-admin-obsidian
git remote add origin <你的私有仓库地址>     # 建议 private：知识库含内部决策与业务口径
git push -u origin master                   # 当前 Vault 分支名以 `git branch` 为准（本机是 master/main 之一）
```

配置后即可 `pwsh scripts/backup-vault.ps1 -Push`（或直接 `git push`）。

> 若希望"离机但不用远端仓库"：把 `-Dest` 指向 **另一块物理盘**、NAS 挂载点或云盘同步目录
> （脚本只写文件，不依赖任何专有 API）。

---

## 4. 定时执行（可选，需你确认后执行）

Windows 任务计划（**下面只是命令，我没有替你注册**）：

```powershell
$action  = New-ScheduledTaskAction -Execute 'pwsh' `
  -Argument '-NoProfile -File "D:\Users\12209\localhostProjects\guarantee-ai-admin\scripts\backup-vault.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At 03:00
Register-ScheduledTask -TaskName 'guarantee-ai-admin-vault-backup' -Action $action -Trigger $trigger -RunLevel Highest
```

> 注意：若 Vault 会被多个 Agent/会话同时写，建议**备份前先确认没有正在进行的写入**（脚本会先提交当前改动，
> 冲突时 git 会拒绝而不是覆盖）。

---

## 5. 与"知识库完整性"的关系（提醒）

本轮更新 Vault 时，本会话**没有接入 `knowledge-os` MCP**（走了降级路径：直接改 Markdown），
因此**没有路径守卫与并发冲突保护**。备份解决的是"丢不丢"，不解决"被并发改坏"。

若要补上后者：把 `knowledge-os` MCP 接到运行时（`.mcp.json` 已存在，Server 在 `tools/knowledge-os-mcp`），
之后 STATE/TASKS/LOG 的写入会走 `expectedHash` 并发校验与原子写。**这属于 C 类小项，本轮未做。**
