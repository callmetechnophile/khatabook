#!/usr/bin/env python3
"""
KHATAWAS Unified Brand Asset Synchronization Tool
=================================================
This tool maintains single-source-of-truth brand identity across the KHATAWAS
ecosystem (Android native application and Web application).

Master Source of Truth:
- Android Launcher Icon: app/src/main/res/mipmap-nodpi/ic_launcher.png
- Master SHA-256 Checksum: 7728775ff9b56520786b70806c750e9db5a76f2c985b15ae0530c2d3b8f5ad35

Derivative Outputs:
- Android: app/src/main/res/mipmap-nodpi/ic_launcher.png
- Web:
    - public/favicon.ico (16x16, 32x32, 48x48)
    - public/assets/khatawas-icon.png (192x192 master)
    - public/assets/khatawas-icon-192.png (192x192)
    - public/assets/khatawas-icon-512.png (512x512)
    - public/assets/khatawas-icon-transparent.png (transparent squircle corners)
    - public/assets/favicon-16x16.png (16x16)
    - public/assets/favicon-32x32.png (32x32)
    - public/assets/apple-touch-icon.png (180x180)
"""

import os
import sys
import shutil
import hashlib
from collections import deque
from PIL import Image
import numpy as np

EXPECTED_MASTER_SHA256 = "7728775ff9b56520786b70806c750e9db5a76f2c985b15ae0530c2d3b8f5ad35"

def safe_copy(src, dst):
    if os.path.abspath(src) != os.path.abspath(dst):
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)

def compute_sha256(filepath):
    with open(filepath, 'rb') as f:
        return hashlib.sha256(f.read()).hexdigest()

def make_transparent_corners(im_rgba):
    arr = np.array(im_rgba)
    w, h = im_rgba.size
    visited = set()
    q = deque([(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)])
    while q:
        x, y = q.popleft()
        if (x, y) in visited:
            continue
        visited.add((x, y))
        r, g, b, a = arr[y, x]
        if r == 0 and g == 0 and b == 0:
            arr[y, x, 3] = 0  # set alpha to 0 for outer corner background
            for dx, dy in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in visited:
                    q.append((nx, ny))
    return Image.fromarray(arr)

def sync_assets():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    root_dir = os.path.dirname(script_dir)
    brand_dir = os.path.join(root_dir, 'brand')
    
    master_candidates = [
        os.path.join(root_dir, 'app', 'src', 'main', 'res', 'mipmap-nodpi', 'ic_launcher.png'),
        os.path.join(brand_dir, 'khatawas-master-icon.png'),
        r'D:\bwb2\app\src\main\res\mipmap-nodpi\ic_launcher.png',
        r'D:\khatawas-site\public\assets\khatawas-icon.png'
    ]
    
    master_path = None
    for cand in master_candidates:
        if os.path.exists(cand):
            sha = compute_sha256(cand)
            if sha == EXPECTED_MASTER_SHA256:
                master_path = cand
                break
    
    if not master_path:
        for cand in master_candidates:
            if os.path.exists(cand):
                master_path = cand
                print(f"[WARN] Using master asset at {cand} with sha {compute_sha256(cand)}")
                break

    if not master_path:
        print("[ERROR] Could not find master KHATAWAS icon asset.")
        sys.exit(1)

    print(f"[1/5] Verified Master Icon: {master_path}")
    print(f"      SHA-256: {compute_sha256(master_path)}")

    # Target locations
    target_roots = [r'D:\bwb2', r'D:\khatawas-site']

    for tr in target_roots:
        if not os.path.exists(tr):
            continue

        print(f"\n[Syncing Target] {tr}")
        tr_brand = os.path.join(tr, 'brand')
        tr_brand_web = os.path.join(tr_brand, 'web')
        tr_brand_android = os.path.join(tr_brand, 'android', 'mipmap-nodpi')
        os.makedirs(tr_brand_web, exist_ok=True)
        os.makedirs(tr_brand_android, exist_ok=True)

        # 1. Master Icon Copy
        brand_master = os.path.join(tr_brand, 'khatawas-master-icon.png')
        safe_copy(master_path, brand_master)
        safe_copy(master_path, os.path.join(tr_brand_android, 'ic_launcher.png'))

        # If android res exists, sync it
        android_res_icon = os.path.join(tr, 'app', 'src', 'main', 'res', 'mipmap-nodpi', 'ic_launcher.png')
        if os.path.exists(os.path.dirname(android_res_icon)):
            safe_copy(master_path, android_res_icon)
            print(f"  -> Synced Android master icon: {android_res_icon}")

        # If web public exists, sync web assets
        web_public = os.path.join(tr, 'public')
        web_assets = os.path.join(web_public, 'assets')
        if os.path.exists(web_public):
            os.makedirs(web_assets, exist_ok=True)
            safe_copy(master_path, os.path.join(web_assets, 'khatawas-icon.png'))
            safe_copy(master_path, os.path.join(web_assets, 'khatawas-icon-192.png'))
            print(f"  -> Synced Web master icon: {os.path.join(web_assets, 'khatawas-icon.png')}")

        # Generate transparent corner image
        master_img = Image.open(master_path).convert('RGBA')
        trans_img = make_transparent_corners(master_img)
        trans_img.save(os.path.join(tr_brand, 'khatawas-master-icon-transparent.png'))

        if os.path.exists(web_assets):
            trans_img.save(os.path.join(web_assets, 'khatawas-icon-transparent.png'))

        # Derivatives
        sizes = {
            'favicon-16x16.png': (16, 16),
            'favicon-32x32.png': (32, 32),
            'apple-touch-icon.png': (180, 180),
            'khatawas-icon-512.png': (512, 512)
        }
        for name, size in sizes.items():
            resized = trans_img.resize(size, Image.Resampling.LANCZOS)
            resized.save(os.path.join(tr_brand_web, name))
            if os.path.exists(web_assets):
                resized.save(os.path.join(web_assets, name))

        # Favicon ICO
        ico_sizes = [(16, 16), (32, 32), (48, 48)]
        trans_img.save(os.path.join(tr_brand_web, 'favicon.ico'), format='ICO', sizes=ico_sizes)
        if os.path.exists(web_public):
            trans_img.save(os.path.join(web_public, 'favicon.ico'), format='ICO', sizes=ico_sizes)
            trans_img.save(os.path.join(web_assets, 'favicon.ico'), format='ICO', sizes=ico_sizes)

        print(f"  -> Generated derived web assets in {tr_brand_web}")

    print("\n[SUCCESS] Brand asset synchronization complete and verified.")

if __name__ == '__main__':
    sync_assets()
