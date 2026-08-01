# Stella Sora TW 自动化资料挖掘流水线

这是一个 Python 3.12 单项目流水线，复用同一工作区 `analysis/tools/` 中已经验证过的本地逆向工具。它完成：

1. 获取官方资源清单，自动计算完整包与增量补丁链；
2. 重建 `data.arcx` / `hotfix.arch`，提取并解密 BAR、AC.DA、XXTEA、LZ4 数据；
3. 从本机 IL2CPP 元数据恢复文件名与字段常量；
4. 解码 protobuf 表，生成分类、繁中/简中和可读 JSON；
5. 与上一版本逐表、逐行、逐字段差分；
6. 校验并生成带 SHA-256 的可复现 ZIP。

## 安装与配置

项目预期放在 StellaSora 工作区的 `automation/` 目录，旁边需要已有的
`analysis/tools/` 和 `analysis/reference/StellaSoraData/`。本仓库不包含游戏文件、
提取数据、官方资源缓存或参考仓库内容。

```powershell
cd automation
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e .
Copy-Item .\config.example.json .\config.json
```

然后修改 `config.json` 中的游戏安装路径。`config.json` 只保存于本机，不会提交。

## 常用命令

在本项目目录执行：

```powershell
# 环境检查
.\run.ps1 doctor

# 正常更新：联网读取最新官方清单和资源
.\run.ps1 run

# 离线复跑：使用 config.json 里的现有缓存
.\run.ps1 run --offline

# 失败后从原运行目录续跑；已完成且签名一致的阶段会跳过
.\run.ps1 run --offline --resume --run-dir ..\analysis\automation\runs\20260802_120000

# 单独比较两个数据集
.\run.ps1 diff <旧数据集目录> <新数据集目录> --output <差分目录>

# 查看最近一次完成运行
.\run.ps1 status
```

直接使用 Python 也可以：

```powershell
python .\run.py doctor
python .\run.py run --offline
```

## 输出结构

每次运行写入独立目录 `analysis/automation/runs/<时间>/`：

```text
state.json                 阶段状态、输入签名、错误与续跑依据
client_snapshot.json       本机客户端版本和哈希
manifests/                 官方清单、下载记录、自动计算的资源链
archives/                  重建后的归档及 BSDIFF provenance
metadata/                  本机元数据解密结果、字符串和字段常量
extract/                   本地资源归档提取结果
archive_name_map.json       文件名证据与 XXH64 本地验证
dataset/                   最终分类可读 JSON 数据集
diff/                      与上一版本的 JSON/Markdown 差分
packages/                  确定性 ZIP 和 SHA-256 清单
diagnostics/               格式或 schema 变化时的机器可读诊断
release.json               本次发布总览
```

`analysis/automation/runs/latest.json` 只记录最近一次成功运行的位置，不覆盖旧版本。

## 后续版本结构变化

版本号没有写死。流水线先探测 BAR、元数据包装和表容器签名，再从
`stella_pipeline/adapters/` 选择适配器。普通内容更新直接运行即可；如果格式改变，流水线会在破坏性解码前停止，并在 `diagnostics/` 写出实际签名和后续处理建议。

新增格式时保留同一个项目，只需增加适配器并调整对应解码模块。只有游戏更换引擎或资源体系完全重做时，才值得拆成另一个项目。

## 数据来源约束

- 数据值来自本机重建并解密的 TW 官方资源；
- 参考仓库只作为候选表名和字段 schema oracle；候选路径必须命中本地 XXH64；
- 任何未映射文件、未知 protobuf 字段或解码失败都会让流水线失败，不会静默漏表；
- 每个阶段只在验证产物后标记完成。

## 发布范围

本仓库只发布自动化总控、版本适配器、差分器、测试和文档。不会发布游戏客户端、
解包后的资源、服务端响应缓存、参考数据仓库或本机路径配置。本项目与游戏开发商及
发行商无隶属或授权关系，请仅处理你有权分析的本地文件。
