#!/usr/bin/env python3
"""
Localizes all external URLs (Google Fonts, CDNs, mesh2motion.org, GitHub raw assets,
previews, GLB/FBX files) inside a built Vite dist/ folder so the Android WebView
runs 100% offline with zero network calls.
"""
import hashlib
import os
import re
import shutil
import sys
import urllib.parse
import urllib.request

TEXT_EXTENSIONS = {".html", ".htm", ".css", ".js", ".mjs", ".json", ".svg"}
ASSET_EXTENSIONS = {
    ".css", ".js", ".mjs", ".woff2", ".woff", ".ttf", ".otf",
    ".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico",
    ".glb", ".gltf", ".fbx", ".bin", ".wasm", ".json", ".hdr", ".exr",
}
SKIP_HOSTS = {
    "www.w3.org",
    "schemas.android.com",
    "appassets.androidplatform.net",
    "localhost",
    "127.0.0.1",
}
USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"

URL_REGEX = re.compile(r"""https?://[^\s"'`<>()\\]+""")
CSS_URL_REGEX = re.compile(r"""url\(\s*(['"]?)(https?://[^)'"]+)\1\s*\)""")


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
    public_dir = os.path.join(temp_web_dir, "public")
    if not os.path.isdir(dist_dir):
        print(f"[localize] dist/ not found at {dist_dir}")
        return

    # Copy any static public/ files (models, previews, animations) if Vite omitted them
    if os.path.isdir(public_dir):
        for root, _, files in os.walk(public_dir):
            rel_root = os.path.relpath(root, public_dir)
            for name in files:
                src = os.path.join(root, name)
                dst = os.path.join(dist_dir, rel_root, name) if rel_root != "." else os.path.join(dist_dir, name)
                if not os.path.exists(dst):
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copy2(src, dst)

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
                    # If downloaded stylesheet (e.g. Google Fonts CSS), localize its @font-face urls too
                    if abs_dest.lower().endswith(".css") or "fonts.googleapis.com" in clean_url:
                        try:
                            css_raw = open(abs_dest, "r", encoding="utf-8", errors="ignore").read()
                            css_localized = localize_css_contents(css_raw, dist_dir, abs_dest)
                            with open(abs_dest, "w", encoding="utf-8") as cf:
                                cf.write(css_localized)
                        except Exception as exc:
                            print(f"[localize] CSS sub-localization error for {abs_dest}: {exc}")

    # Second pass: rewrite references in dist/ to relative local paths and strip preconnect hints
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

            if ext == ".css":
                updated = localize_css_contents(updated, dist_dir, fpath)

            if updated != text:
                with open(fpath, "w", encoding="utf-8") as f:
                    f.write(updated)

    print(f"[localize] Localized {len(url_map)} external URLs into {dist_dir}/offline_vendor/")


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "temp_web"
    process_dist(target)
