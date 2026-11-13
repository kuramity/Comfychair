#!/usr/bin/env python3
"""从你自己的 ComfyUI 服务器采集模型清单与介绍，生成 App 用的 model_intros.json。

功能定位
    ComfyChair 的「模型介绍」数据不是仓库内置的（开源版故意留空，避免泄漏开发者
    服务器上有哪些模型）。本脚本从**你自己的**服务器采集：先拿模型清单，再按
    SHA-256 反查 Civitai、按文件名补查 Civitai 搜索与 HuggingFace，最后输出
    与 App 的 ModelIntroTable 完全兼容的 model_intros.json。

入口与用法
    入口：本文件（纯标准库，无第三方依赖）

    # 1) 扫描本地 models 目录，算哈希后联网反查（推荐，命中率最高）
    python tools/collect_model_intros.py --models-dir /path/to/ComfyUI/models \\
        --out app/src/main/assets/model_intros.json

    # 2) 只从运行中的服务器拉清单，按文件名反查（没有本地文件，算不出哈希）
    python tools/collect_model_intros.py --server http://127.0.0.1:8188 \\
        --out app/src/main/assets/model_intros.json

    # 3) 增量：已有 model_intros.json 里的条目保留，只补新增模型
    python tools/collect_model_intros.py --models-dir ... --out ... \\
        --seed app/src/main/assets/model_intros.json

    # 4) 只看哪些模型没查到介绍（用来人工补）
    python tools/collect_model_intros.py --models-dir ... --report-only

依赖
    - Python 3.9+，仅标准库（urllib / hashlib / json）
    - Civitai 公开 API 不需要 token；HuggingFace 公共模型不需要 token
      （私有仓库需设环境变量 HF_TOKEN，脚本会自动带上）

安全约束
    - 只允许访问白名单公网主机（civitai.com / civitai.red / huggingface.co），
      解析后 IP 必须是公网（拒绝环回 / 私网 / 链路本地 / 组播 / 未指定）
    - ComfyUI 服务器地址只允许环回或私网（就是你自己那台机器）
    - 禁止跟随 HTTP 重定向（防 DNS rebinding）
    - 所有文件读写经 checked_open 归一化校验（防路径穿越）

关联
    - App 端读取逻辑：app/src/main/java/sh/hnet/comfychair/model/ModelIntroTable.kt
      （字段名必须对齐；models 为空时 App 会安全退化为「无介绍」）
    - App 端远端同步：ModelIntroSync.kt（从 /view?filename=model_intros.json
      &subfolder=comfychair&type=output 拉取）
    - 规则依据：AGENTS.md「脚本书写规范（Mimosa 安全扫描合规）」
"""

import argparse
import hashlib
import ipaddress
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote, urlparse

CIVITAI_API = "https://civitai.com/api/v1"
HF_API = "https://huggingface.co/api"

# 允许联网的公网主机白名单（只有这几个外部 API）
PUBLIC_HOSTS = {"civitai.com", "www.civitai.com", "civitai.red", "huggingface.co"}

# ComfyUI loader 节点 → (模型名输入键, 类别文案)。
# 插件自定义节点没列到也不会漏：脚本另有「扫描所有 *_name 形式选择型输入」的兜底。
LOADER_INPUTS = {
    "CheckpointLoaderSimple": ("ckpt_name", "大模型（Checkpoint）"),
    "CheckpointLoader": ("ckpt_name", "大模型（Checkpoint）"),
    "UNETLoader": ("unet_name", "扩散模型（UNET）"),
    "UnetLoaderGGUF": ("unet_name", "扩散模型（UNET/GGUF）"),
    "VAELoader": ("vae_name", "VAE"),
    "CLIPLoader": ("clip_name", "文本编码器（CLIP）"),
    "DualCLIPLoader": ("clip_name1", "文本编码器（CLIP）"),
    "LoraLoader": ("lora_name", "LoRA"),
    "LoraLoaderModelOnly": ("lora_name", "LoRA"),
    "UpscaleModelLoader": ("model_name", "放大模型"),
    "ControlNetLoader": ("control_net_name", "ControlNet"),
    "CLIPVisionLoader": ("clip_name", "视觉编码器"),
    "StyleModelLoader": ("style_model_name", "风格模型"),
}

MODEL_EXT = {".safetensors", ".ckpt", ".pt", ".pth", ".bin", ".gguf", ".sft"}

USER_AGENT = "ComfyChair-intro-collector/1.0 (+https://github.com/)"


# ---------------------------------------------------------------- 路径与 IO


def checked_open(root, target, mode):
    """校验目标路径落在 root 内再打开（校验与打开合一，防路径穿越）。"""
    root_norm = os.path.normcase(os.path.abspath(root))
    full = os.path.normcase(os.path.abspath(os.path.join(root, target)))
    if os.path.commonpath([root_norm, full]) != root_norm:
        raise SystemExit("拒绝越界路径：" + target)
    return open(full, mode)


def read_json(root, name, default):
    """读 root 下的 JSON 文件；不存在或解析失败返回 default。"""
    path = os.path.join(root, name)
    if not os.path.isfile(path):
        return default
    try:
        with checked_open(root, name, "r") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return default


def write_json(root, name, payload):
    """把 payload 写成 root 下的 JSON 文件（缩进 1，与 App 内置格式一致）。"""
    with checked_open(root, name, "w") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=1)
        handle.write("\n")


# ---------------------------------------------------------------- 受限 HTTP


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """禁止跟随重定向：清单/接口跳转到别处时直接失败，防 DNS rebinding。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _ip_is_public(text):
    """判断 IP 是否公网可路由（拒绝环回/私网/链路本地/组播/保留/未指定）。"""
    addr = ipaddress.ip_address(text)
    if addr.is_loopback or addr.is_private or addr.is_link_local:
        return False
    if addr.is_multicast or addr.is_unspecified or addr.is_reserved:
        return False
    return True


def _ip_is_local(text):
    """判断 IP 是否本机/局域网（ComfyUI 服务器的合法取值）。"""
    addr = ipaddress.ip_address(text)
    return addr.is_loopback or addr.is_private


def _resolve(host):
    """解析域名的全部 A/AAAA 记录。"""
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        return []
    return [info[4][0] for info in infos]


def assert_public_url(url):
    """公网 API 的三重校验：协议 → 白名单主机 → 解析 IP 必须公网。"""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise SystemExit("拒绝非 http(s) 地址：" + url)
    host = (parsed.hostname or "").lower()
    if host not in PUBLIC_HOSTS:
        raise SystemExit("拒绝非白名单主机：" + host)
    addresses = _resolve(host)
    if not addresses:
        raise SystemExit("域名解析失败：" + host)
    for address in addresses:
        if not _ip_is_public(address):
            raise SystemExit("拒绝解析到内网地址的主机：" + host + " -> " + address)
    return url


def assert_local_url(url):
    """ComfyUI 服务器地址校验：只允许环回或私网。"""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise SystemExit("拒绝非 http(s) 地址：" + url)
    host = (parsed.hostname or "").lower()
    if not host:
        raise SystemExit("地址缺少主机名：" + url)
    # 纯 IP 直接判；域名（如 localhost / 内网主机名）解析后再判
    try:
        if not _ip_is_local(host):
            raise SystemExit("只允许本机或局域网地址：" + host)
        return url
    except ValueError:
        pass
    addresses = _resolve(host)
    if not addresses:
        raise SystemExit("主机名解析失败：" + host)
    for address in addresses:
        if not _ip_is_local(address):
            raise SystemExit("只允许本机或局域网地址：" + host + " -> " + address)
    return url


def fetch_text(url, local=False):
    """取 URL 的文本内容（先做地址校验，再发请求，禁重定向）。"""
    if local:
        assert_local_url(url)
    else:
        assert_public_url(url)

    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    if not local and "huggingface.co" in url:
        token = os.environ.get("HF_TOKEN", "")
        if token:
            headers["Authorization"] = "Bearer " + token

    request = urllib.request.Request(url, headers=headers)
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(request, timeout=25) as response:
            return response.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, urllib.error.HTTPError, OSError):
        return None


def fetch_json(url, local=False):
    text = fetch_text(url, local=local)
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        return None


# ---------------------------------------------------------------- 模型清单


def list_from_dir(models_dir):
    """扫本地 models 目录，返回 {相对路径: 类别}。"""
    found = {}
    root = os.path.abspath(models_dir)
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if os.path.splitext(name)[1].lower() not in MODEL_EXT:
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace("\\", "/")
            found[rel] = guess_kind(rel)
    return found


def list_from_server(server):
    """从运行中的 ComfyUI 拉 /object_info，提取全部可选模型。"""
    base = server.rstrip("/")
    doc = fetch_json(base + "/object_info", local=True)
    if not doc:
        raise SystemExit("无法访问 " + base + "/object_info（服务器没开或地址不对）")

    found = {}
    for node_name, node in doc.items():
        if not isinstance(node, dict):
            continue
        inputs = (node.get("input") or {}).get("required") or {}
        for key, spec in inputs.items():
            if not isinstance(spec, list) or not spec:
                continue
            options = spec[0]
            if not isinstance(options, list) or not options:
                continue
            # 只收「看起来是模型文件名」的选择型输入
            if not key.endswith(("_name", "_name1", "_file", "_model")):
                continue
            kind = LOADER_INPUTS.get(node_name, (None, None))[1]
            for item in options:
                if not isinstance(item, str) or not item:
                    continue
                if os.path.splitext(item)[1].lower() not in MODEL_EXT:
                    continue
                found[item] = kind or guess_kind(item)
    return found


def guess_kind(rel_path):
    """按目录名与文件名推断模型类别（只有文件名时也能分类）。"""
    low = rel_path.lower()
    if "lora" in low:
        return "LoRA"
    if "vae" in low:
        return "VAE"
    if "clip" in low or "text_encoder" in low or "t5" in low:
        return "文本编码器（CLIP）"
    if "upscale" in low or "esrgan" in low or "swinir" in low:
        return "放大模型"
    if "controlnet" in low:
        return "ControlNet"
    if "embedding" in low or "textual_inversion" in low:
        return "Embedding"
    if "diffusion_models" in low or "unet" in low:
        return "扩散模型（UNET）"
    if "checkpoints" in low:
        return "大模型（Checkpoint）"
    return "模型"


# ---------------------------------------------------------------- 哈希


def sha256_prefix(models_dir, rel_path, keep=10):
    """算 SHA-256 前 keep 位（Civitai 的 AutoV2 就是前 10 位）。"""
    digest = hashlib.sha256()
    with checked_open(models_dir, rel_path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()[:keep]


# ---------------------------------------------------------------- Civitai


def civitai_by_hash(sha):
    """按 SHA-256 前缀查 model-version，再补查 model 详情。

    为什么必须二次查 /models/{id}：by-hash 返回的 model 子对象是裁剪版，
    作者、简介、标签都在完整详情里，只取 by-hash 会得到一堆空字段。
    """
    version = fetch_json(CIVITAI_API + "/model-versions/by-hash/" + quote(sha))
    if not version:
        return None
    model_id = version.get("modelId")
    model = fetch_json(CIVITAI_API + "/models/" + quote(str(model_id))) if model_id else None
    return {"version": version, "model": model}


def civitai_search(filename):
    """按文件名搜索兜底（本地没有文件、算不出哈希时用）。"""
    stem = os.path.splitext(os.path.basename(filename))[0]
    for noise in ("-fp16", "_fp16", "-fp8", "_fp8", "-int8", "_int8", "-Q4_K_M", "-Q8_0"):
        stem = stem.replace(noise, "")
    data = fetch_json(CIVITAI_API + "/models?limit=3&query=" + quote(stem[:60]))
    if not data:
        return None
    items = data.get("items") or []
    if not items:
        return None
    return {"version": None, "model": items[0]}


def intro_from_civitai(hit):
    """把 Civitai 返回整理成 App 的字段。"""
    model = hit.get("model") or {}
    version = hit.get("version") or {}
    creator = model.get("creator") or {}
    desc = (version.get("description") or model.get("description") or "").strip()
    model_id = model.get("id")
    return {
        "name": model.get("name") or "",
        "type": model.get("type") or "",
        "base": (version.get("baseModel") or "").strip(),
        "author": creator.get("username") or "",
        "version": version.get("name") or "",
        "summary": first_sentence(desc),
        "intro_original": desc[:4000],
        "source": "civitai",
        "url": (CIVITAI_API + "/models/" + str(model_id)) if model_id else "",
        "tags": model.get("tags") or [],
    }


def first_sentence(text, limit=180):
    """取第一段做摘要（App 的 summary 是列表页一行展示用的）。"""
    cleaned = " ".join(text.split())
    if not cleaned:
        return ""
    for sep in ("。", ". ", "\n"):
        pos = cleaned.find(sep)
        if 0 < pos < limit:
            return cleaned[: pos + len(sep)].strip()
    return cleaned[:limit].strip()


# ---------------------------------------------------------------- HuggingFace


def hf_search(filename):
    """按文件名搜 HuggingFace 仓库。"""
    stem = os.path.splitext(os.path.basename(filename))[0]
    data = fetch_json(HF_API + "/models?search=" + quote(stem[:60]) + "&limit=3")
    if not isinstance(data, list) or not data:
        return None
    return data[0]


def intro_from_hf(repo):
    """把 HF 搜索结果整理成 App 字段（再用 model card 补简介）。"""
    repo_id = repo.get("modelId") or repo.get("id") or ""
    card = ""
    if repo_id:
        card = fetch_text("https://huggingface.co/" + repo_id + "/raw/main/README.md") or ""
    return {
        "name": repo_id.split("/")[-1] if repo_id else "",
        "type": "",
        "base": "",
        "author": repo_id.split("/")[0] if "/" in repo_id else "",
        "version": "",
        "summary": first_sentence(card),
        "intro_original": card[:4000],
        "source": "huggingface",
        "url": "https://huggingface.co/" + repo_id if repo_id else "",
        "tags": repo.get("tags") or [],
    }


# ---------------------------------------------------------------- 单条记录


def build_entry(rel, kind, models_dir, cache, cache_dir):
    """为单个模型生成一条介绍记录。"""
    filename = os.path.basename(rel)
    entry = {
        "file": filename,
        "rel": rel if "/" in rel else "",
        "kind": kind,
        "source": "",
        "missing": True,
    }

    # 1) 有本地文件才算得出哈希（哈希结果按 大小+修改时间 缓存，重跑不用重算大文件）
    sha = ""
    if models_dir:
        abs_path = os.path.join(models_dir, rel.replace("/", os.sep))
        try:
            stat = os.stat(abs_path)
        except OSError:
            stat = None
        if stat:
            stored = cache.get(rel) or {}
            if stored.get("size") == stat.st_size and stored.get("mtime") == int(stat.st_mtime):
                sha = stored.get("sha", "")
            else:
                try:
                    sha = sha256_prefix(models_dir, rel)
                except OSError:
                    sha = ""
                cache[rel] = {
                    "size": stat.st_size,
                    "mtime": int(stat.st_mtime),
                    "sha": sha,
                }

    if sha:
        hit = civitai_by_hash(sha)
        if hit:
            entry.update(intro_from_civitai(hit))
            entry["missing"] = False
            return entry

    hit = civitai_search(filename)
    if hit:
        entry.update(intro_from_civitai(hit))
        entry["missing"] = False
        return entry

    repo = hf_search(filename)
    if repo:
        entry.update(intro_from_hf(repo))
        entry["missing"] = False
        return entry

    entry["summary"] = f"「{filename}」暂未查到在线介绍，可手动补充。"
    return entry


# ---------------------------------------------------------------- 主流程


def main():
    parser = argparse.ArgumentParser(
        description="从你的 ComfyUI 采集模型清单与介绍，生成 App 用的 model_intros.json"
    )
    parser.add_argument("--models-dir", help="ComfyUI 的 models 目录（能算哈希，命中率最高）")
    parser.add_argument("--server", help="运行中的 ComfyUI 地址，如 http://127.0.0.1:8188")
    parser.add_argument("--out", help="输出的 model_intros.json 路径")
    parser.add_argument("--seed", help="已有的 model_intros.json（增量合并，保留人工补充）")
    parser.add_argument("--report-only", action="store_true",
                        help="只列出没查到介绍的模型，不写输出文件")
    parser.add_argument("--limit", type=int, default=0, help="只处理前 N 个（调试用）")
    args = parser.parse_args()

    if not args.models_dir and not args.server:
        parser.error("至少要给 --models-dir 或 --server 之一")

    if args.models_dir:
        found = list_from_dir(args.models_dir)
        print(f"扫描 {args.models_dir}：{len(found)} 个模型")
    else:
        found = list_from_server(args.server)
        print(f"从 {args.server} 拉取：{len(found)} 个模型")

    items = sorted(found.items())
    if args.limit > 0:
        items = items[: args.limit]

    cache_dir = os.getcwd()
    cache = read_json(cache_dir, "model_intros.hashcache.json", {})
    seed = {}
    if args.seed:
        seed_doc = read_json(os.path.dirname(os.path.abspath(args.seed)),
                             os.path.basename(args.seed), {})
        models = seed_doc.get("models")
        seed = models if isinstance(models, dict) else {}

    models = dict(seed)
    missing = []
    started = time.time()

    for index, (rel, kind) in enumerate(items, start=1):
        key = os.path.basename(rel).lower()
        if key in seed and not seed[key].get("missing"):
            continue  # 已有可用介绍，增量跳过（不重复打 API）
        entry = build_entry(rel, kind, args.models_dir, cache, cache_dir)
        models[key] = entry
        if entry.get("missing"):
            missing.append(rel)
        print(f"[{index}/{len(items)}] {rel} -> {entry.get('source') or '未命中'}")

    if cache:
        try:
            write_json(cache_dir, "model_intros.hashcache.json", cache)
        except OSError:
            print("警告：哈希缓存写入失败（不影响结果）")

    if args.report_only:
        print(f"\n未查到介绍：{len(missing)} 个")
        for name in missing:
            print("  " + name)
        return 0

    if not args.out:
        parser.error("写输出需要 --out（或用 --report-only 只看报告）")

    doc = {
        "version": 1,
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "count": len(models),
        "note": "由 tools/collect_model_intros.py 生成；键为模型文件名（小写），rel 为相对 models/ 的路径",
        "models": models,
    }
    out_dir = os.path.dirname(os.path.abspath(args.out)) or "."
    write_json(out_dir, os.path.basename(args.out), doc)

    print(f"\n已写入 {args.out}：{len(models)} 条，未命中 {len(missing)} 条，"
          f"耗时 {time.time() - started:.1f}s")
    if missing:
        print("未命中的模型可人工补（补完重跑会增量保留）：")
        for name in missing[:30]:
            print("  " + name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
