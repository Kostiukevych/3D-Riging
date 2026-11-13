#!/usr/bin/env python3
"""
Prepares Mesh2Motion Vite source & post-processes dist/ for 100% offline Android WebView use:
1. --prepare-source <dir>:
   - Enforces `base: './'` in vite.config.* and package.json build script.
   - Rewrites root-leading asset/skeleton/model paths (`/rigs/...`, `/models/...`, `/*.glb`, etc.)
     to relative `./...` paths across src/, public/, and index.html.
   - Verifies Git LFS binary files (.glb, .fbx, .gltf, .bin) are materialized (not pointer stubs).
2. <dir> (default post-build pass):
   - Copies any missing static files from public/ or static/ into dist/.
   - Rewrites any remaining root-leading asset paths inside dist/ JS/HTML/CSS/JSON bundles to `./...`.
   - Downloads & localizes external URLs (Google Fonts, CDNs, mesh2motion.org, GitHub raw assets)
     into dist/offline_vendor/ so Android WebView runs completely offline.
   - Verifies no Git LFS pointer stubs remain in dist/.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

TEXT_EXTENSIONS = {".html", ".htm", ".css", ".js", ".mjs", ".ts", ".tsx", ".jsx", ".json", ".svg"}
ASSET_EXTENSIONS = {
    ".css", ".js", ".mjs", ".woff2", ".woff", ".ttf", ".otf",
    ".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico",
    ".glb", ".gltf", ".fbx", ".obj", ".dae", ".bin", ".wasm", ".json", ".hdr", ".exr",
}
SKIP_HOSTS = {
    "www.w3.org",
    "schemas.android.com",
    "appassets.androidplatform.net",
    "localhost",
    "127.0.0.1",
}
USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
LFS_POINTER_PREFIX = b"version https://git-lfs.github.com/spec/v1"

URL_REGEX = re.compile(r"""https?://[^\s"'`<>()\\]+""")
CSS_URL_REGEX = re.compile(r"""url\(\s*(['"]?)(https?://[^)'"]+)\1\s*\)""")

# Root-leading asset directory prefixes (e.g. "/rigs/...", `/models/${id}.glb`)
ROOT_ASSET_DIR_REGEX = re.compile(
    r"""(?P<q>["'`])/(?P<path>(?:rigs|models|skeletons|animations|assets|images|img|textures|fonts|icons|meshes|static|media)/[^"'`\s<>]*)"""
)
# Root-leading direct asset file paths (e.g. "/human.glb", "/favicon.svg")
ROOT_ASSET_FILE_REGEX = re.compile(
    r"""(?P<q>["'`])/(?P<path>[A-Za-z0-9_.\-/]+\.(?:glb|gltf|fbx|obj|dae|bin|wasm|png|jpe?g|webp|svg|woff2?|ttf|json))(?=[?"'`])""",
    re.IGNORECASE,
)


def rewrite_root_asset_paths(text: str) -> str:
    updated = ROOT_ASSET_DIR_REGEX.sub(r"\g<q>./\g<path>", text)
    updated = ROOT_ASSET_FILE_REGEX.sub(r"\g<q>./\g<path>", updated)
    return updated


def patch_vite_and_package_config(repo_dir: str) -> None:
    patched_vite = False
    for cfg_name in ("vite.config.ts", "vite.config.js", "vite.config.mts", "vite.config.mjs"):
        cfg_path = os.path.join(repo_dir, cfg_name)
        if not os.path.isfile(cfg_path):
            continue
        raw = open(cfg_path, "r", encoding="utf-8", errors="ignore").read()
        updated = raw
        if re.search(r"\bbase\s*:", updated):
            updated = re.sub(r"""\bbase\s*:\s*['"][^'"]*['"]""", "base: './'", updated)
        elif "defineConfig(" in updated:
            updated = re.sub(r"defineConfig\(\s*\{", "defineConfig({\n  base: './',", updated, count=1)
        elif "export default" in updated and "{" in updated:
            updated = re.sub(r"export\s+default\s*\{", "export default {\n  base: './',", updated, count=1)

        if updated != raw:
            with open(cfg_path, "w", encoding="utf-8") as f:
                f.write(updated)
            print(f"[localize] Patched {cfg_name} -> base: './'")
        patched_vite = True

    if not patched_vite:
        cfg_path = os.path.join(repo_dir, "vite.config.js")
        with open(cfg_path, "w", encoding="utf-8") as f:
            f.write("export default {\n  base: './',\n};\n")
        print("[localize] Created vite.config.js with base: './'")

    pkg_path = os.path.join(repo_dir, "package.json")
    if os.path.isfile(pkg_path):
        try:
            with open(pkg_path, "r", encoding="utf-8") as f:
                pkg = json.load(f)
            scripts = pkg.get("scripts", {})
            build_cmd = scripts.get("build", "")
            if isinstance(build_cmd, str) and "vite build" in build_cmd and "--base" not in build_cmd:
                scripts["build"] = build_cmd.replace("vite build", "vite build --base=./")
                pkg["scripts"] = scripts
                with open(pkg_path, "w", encoding="utf-8") as f:
                    json.dump(pkg, f, indent=2)
                print(f"[localize] Updated package.json build script: {scripts['build']}")
        except Exception as exc:
            print(f"[localize] WARNING: Could not patch package.json: {exc}")


def check_lfs_pointers(search_dir: str, attempt_pull_in: str | None = None) -> list[str]:
    pointers: list[str] = []
    binary_exts = {".glb", ".fbx", ".gltf", ".bin", ".zip", ".png", ".webp", ".wasm"}
    for root, _, files in os.walk(search_dir):
        if "node_modules" in root.split(os.sep):
            continue
        for name in files:
            _, ext = os.path.splitext(name.lower())
            if ext not in binary_exts:
                continue
            fpath = os.path.join(root, name)
            try:
                with open(fpath, "rb") as f:
                    head = f.read(64)
                if head.startswith(LFS_POINTER_PREFIX):
                    pointers.append(fpath)
            except Exception:
                continue

    if pointers and attempt_pull_in:
        print(f"[localize] Detected {len(pointers)} Git LFS pointer stubs; running git lfs pull...")
        subprocess.run(["git", "-C", attempt_pull_in, "lfs", "pull"], check=False)
        return check_lfs_pointers(search_dir, attempt_pull_in=None)
    return pointers


def prepare_source(temp_web_dir: str) -> None:
    patch_vite_and_package_config(temp_web_dir)

    pointers = check_lfs_pointers(temp_web_dir, attempt_pull_in=temp_web_dir)
    if pointers:
        raise RuntimeError(
            "Found unresolved Git LFS pointer files instead of binary 3D models: "
            + ", ".join(pointers[:10])
        )

    rewritten_files = 0
    for target_rel in ("index.html", "src", "public"):
        target_path = os.path.join(temp_web_dir, target_rel)
        if os.path.isfile(target_path):
            candidates = [target_path]
        elif os.path.isdir(target_path):
            candidates = []
            for root, _, files in os.walk(target_path):
                for name in files:
                    _, ext = os.path.splitext(name.lower())
                    if ext in TEXT_EXTENSIONS:
                        candidates.append(os.path.join(root, name))
        else:
            continue

        for fpath in candidates:
            try:
                raw = open(fpath, "r", encoding="utf-8", errors="ignore").read()
            except Exception:
                continue
            updated = rewrite_root_asset_paths(raw)
            if updated != raw:
                with open(fpath, "w", encoding="utf-8") as f:
                    f.write(updated)
                rewritten_files += 1

    print(f"[localize] Prepared source in {temp_web_dir} (rewrote paths in {rewritten_files} files)")


def download_url(url: str, dest_path: str) -> bool:
    if os.path.exists(dest_path) and os.path.getsize(dest_path) > 0:
        return True
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            data = resp.read()
        with open(dest_path, "wb") as f:
            f.write(data)
        return True
    except Exception as exc:
        print(f"[localize] WARNING: Failed to download {url}: {exc}")
        return False


def local_rel_path_for_url(url: str, default_ext: str = "") -> str:
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc.split(":")[0].lower()
    raw_path = parsed.path.lstrip("/")
    if not raw_path:
        raw_path = "index"
    _, ext = os.path.splitext(raw_path)
    if not ext and default_ext:
        raw_path = f"{raw_path}{default_ext}"
    if parsed.query:
        q_hash = hashlib.sha1(parsed.query.encode("utf-8")).hexdigest()[:8]
        stem, ext2 = os.path.splitext(raw_path)
        raw_path = f"{stem}_{q_hash}{ext2}"
    return f"offline_vendor/{host}/{raw_path}"


def is_downloadable_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc.split(":")[0].lower()
    if not host or host in SKIP_HOSTS:
        return False
    if "fonts.googleapis.com" in host or "fonts.gstatic.com" in host:
        return True
    if any(cdn in host for cdn in ("cdn.", "jsdelivr", "unpkg", "cloudflare", "mesh2motion", "githubusercontent", "github.com")):
        return True
    _, ext = os.path.splitext(parsed.path.lower())
    return ext in ASSET_EXTENSIONS


def localize_css_contents(css_text: str, dist_dir: str, css_file_path: str) -> str:
    def replace_css_url(match: re.Match) -> str:
        ext_url = match.group(2)
        if not is_downloadable_url(ext_url):
            return match.group(0)
        rel_from_dist = local_rel_path_for_url(ext_url)
        abs_dest = os.path.join(dist_dir, rel_from_dist)
        if download_url(ext_url, abs_dest):
            rel_from_css = os.path.relpath(abs_dest, os.path.dirname(css_file_path)).replace(os.sep, "/")
            return f"url('{rel_from_css}')"
        return match.group(0)

    return CSS_URL_REGEX.sub(replace_css_url, css_text)


def process_dist(temp_web_dir: str) -> None:
    dist_dir = os.path.join(temp_web_dir, "dist")
    if not os.path.isdir(dist_dir):
        print(f"[localize] dist/ not found at {dist_dir}")
        return

    # Copy any static public/ or static/ files (models, rigs, previews, animations) if Vite omitted them
    for static_name in ("public", "static"):
        static_dir = os.path.join(temp_web_dir, static_name)
        if not os.path.isdir(static_dir):
            continue
        for root, _, files in os.walk(static_dir):
            rel_root = os.path.relpath(root, static_dir)
            for name in files:
                src = os.path.join(root, name)
                dst = os.path.join(dist_dir, rel_root, name) if rel_root != "." else os.path.join(dist_dir, name)
                if not os.path.exists(dst):
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy2(src, dst)

    # Ensure zero Git LFS pointers remain in dist/
    pointers = check_lfs_pointers(dist_dir)
    if pointers:
        raise RuntimeError(
            "dist/ contains Git LFS pointer stubs instead of binary models: " + ", ".join(pointers[:10])
        )

    # First pass: collect and download external URLs referenced in dist/
    url_map: dict[str, str] = {}
    for root, _, files in os.walk(dist_dir):
        for name in files:
            _, ext = os.path.splitext(name.lower())
            if ext not in TEXT_EXTENSIONS:
                continue
            fpath = os.path.join(root, name)
            try:
                content = open(fpath, "r", encoding="utf-8", errors="ignore").read()
            except Exception:
                continue

            for url in set(URL_REGEX.findall(content)):
                clean_url = url.rstrip(".,;:")
                if clean_url in url_map or not is_downloadable_url(clean_url):
                    continue
                default_ext = ".css" if "fonts.googleapis.com" in clean_url else ""
                rel_local = local_rel_path_for_url(clean_url, default_ext=default_ext)
                abs_dest = os.path.join(dist_dir, rel_local)
                if download_url(clean_url, abs_dest):
                    url_map[clean_url] = rel_local
                    if abs_dest.lower().endswith(".css") or "fonts.googleapis.com" in clean_url:
                        try:
                            css_raw = open(abs_dest, "r", encoding="utf-8", errors="ignore").read()
                            css_localized = localize_css_contents(css_raw, dist_dir, abs_dest)
                            with open(abs_dest, "w", encoding="utf-8") as cf:
                                cf.write(css_localized)
                        except Exception as exc:
                            print(f"[localize] CSS sub-localization error for {abs_dest}: {exc}")

    # Second pass: rewrite external URLs, root-leading asset paths, and preconnect tags in dist/
    for root, _, files in os.walk(dist_dir):
        for name in files:
            _, ext = os.path.splitext(name.lower())
            if ext not in TEXT_EXTENSIONS:
                continue
            fpath = os.path.join(root, name)
            try:
                text = open(fpath, "r", encoding="utf-8", errors="ignore").read()
            except Exception:
                continue

            updated = text
            if name.lower() in ("index.html", "index.htm"):
                updated = re.sub(
                    r"""<link[^>]+rel=["'](?:preconnect|dns-prefetch)["'][^>]*>\s*""",
                    "",
                    updated,
                    flags=re.IGNORECASE,
                )

            for ext_url, rel_from_dist in url_map.items():
                if ext_url in updated:
                    abs_target = os.path.join(dist_dir, rel_from_dist)
                    rel_from_file = os.path.relpath(abs_target, os.path.dirname(fpath)).replace(os.sep, "/")
                    if not rel_from_file.startswith("."):
                        rel_from_file = f"./{rel_from_file}"
                    updated = updated.replace(ext_url, rel_from_file)

            updated = rewrite_root_asset_paths(updated)

            if ext == ".css":
                updated = localize_css_contents(updated, dist_dir, fpath)

            if updated != text:
                with open(fpath, "w", encoding="utf-8") as f:
                    f.write(updated)

    print(f"[localize] Localized {len(url_map)} external URLs and relative asset paths in {dist_dir}")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "--prepare-source":
        prepare_source(sys.argv[2])
    else:
        target = sys.argv[1] if len(sys.argv) > 1 else "temp_web"
        process_dist(target)
