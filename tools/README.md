# tools/ — 随仓库提供的维护脚本

面向开源用户的自包含工具。纯标准库 Python，无第三方依赖，在仓库根目录执行。

---

## collect_model_intros.py — 模型介绍采集

**功能定位**
从**你自己**的 ComfyUI 采集模型清单与介绍，生成 App 的「模型介绍」数据
`app/src/main/assets/model_intros.json`。开源版该文件是空表（不内置开发者的模型清单），
要用这个功能就跑本脚本。触发场景：首次配置完自己的服务器、下载了新模型、想补介绍。

**入口与用法**

```bash
# 1) 扫本地 models 目录，算 SHA-256 后查 Civitai（命中率最高）
python tools/collect_model_intros.py \
    --models-dir /path/to/ComfyUI/models \
    --out app/src/main/assets/model_intros.json

# 2) 只从运行中的服务器拉清单，按文件名反查（没有本地文件，算不出哈希）
python tools/collect_model_intros.py \
    --server http://127.0.0.1:8188 \
    --out app/src/main/assets/model_intros.json

# 3) 增量：保留已有介绍，只补新增模型
python tools/collect_model_intros.py --models-dir ... --out ... \
    --seed app/src/main/assets/model_intros.json

# 4) 只列没查到介绍的模型（人工补用）
python tools/collect_model_intros.py --models-dir ... --report-only

# 5) 只处理前 5 个（调试）
python tools/collect_model_intros.py --models-dir ... --out ... --limit 5
```

反查顺序：SHA-256 命中 Civitai model-version → 二次查 `/models/{id}` 补作者/简介/标签
→ 没哈希按文件名搜 Civitai → 再查 HuggingFace（读 model card）。
未命中的会列成清单，建议手工补后再跑（`--seed` 会保留你补的内容）。

**依赖**
- Python 3.9+，仅标准库
- 联网：Civitai / HuggingFace 公开 API（无需 token；私有 HF 仓库设 `HF_TOKEN` 环境变量）
- 哈希缓存写在当前目录 `model_intros.hashcache.json`（可删，删了重跑只是慢）

**安全约束**
只允许访问白名单公网主机（civitai.com / civitai.red / huggingface.co），解析后 IP
必须是公网（拒绝环回/私网/链路本地/组播）；ComfyUI 服务器地址只允许环回或私网；
禁止跟随 HTTP 重定向；所有文件读写经 `abspath + commonpath` 校验。

**关联**
- App 读取：`app/src/main/java/sh/hnet/comfychair/model/ModelIntroTable.kt`
  （字段名必须对齐；`models` 为空时 App 安全退化为「无介绍」）
- 远端同步：`ModelIntroSync.kt`（从 `/view?filename=model_intros.json&subfolder=comfychair&type=output` 拉）
- 生成后推到服务器供 App 拉取：
  ```bash
  curl -F "image=@app/src/main/assets/model_intros.json" \
       -F "subfolder=comfychair" -F "type=output" -F "overwrite=true" \
       http://127.0.0.1:8188/upload/image
  ```

---

## 内置工作流清单（开源版）

| 文件 | 名称 | 类型 |
|---|---|---|
| `prebuilt_04_krea2_hires_g1` | 文生图·Krea2 初生图 | TTI |
| `prebuilt_09_krea2_edit_single` | 图生图·单图编辑 | ITI_EDITING |
| `prebuilt_14_minimaxh3_t2v` | 文生视频·MiniMaxH3 | TTV |
| `prebuilt_13_minimaxh3_i2v` | 图生视频·MiniMaxH3 | ITV |

4 个模板的模型名与提示词全部留空，用户从自己服务器的下拉框选。

## 想自己加工作流？

- 占位符规范与检查项：`docs/预置工作流规范.md`
- 3D 生成（TTI_3D / ITI_3D）不内置模板，但 App 功能代码完整保留：
  在 ComfyUI 里搭好 3D 工作流 → 导出 API 格式 → App 里导入即可生成与预览 `.glb`
