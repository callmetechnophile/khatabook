path1 = r'D:\khatawas-site\docs\KHATAWAS_LANDING_PAGE_REDESIGN.md'
path2 = r'D:\bwb2\docs\KHATAWAS_LANDING_PAGE_REDESIGN.md'

with open(path1, 'r', encoding='utf-8') as f:
    content = f.read()

unified_brand_section = """### 2.3 Unified Brand Icon & Cross-Platform Integrity
To maintain visual consistency between the Android native app and the web application:
- **Canonical Master Icon Asset**: `app/src/main/res/mipmap-nodpi/ic_launcher.png` (Android launcher icon).
- **Master SHA-256 Checksum**: `7728775ff9b56520786b70806c750e9db5a76f2c985b15ae0530c2d3b8f5ad35`.
- **Artwork Integrity**: Both platforms display the identical artwork without independent redrawing, recoloring, cropping, or distortions.
- **Shared Brand Asset Directory (`brand/`)**:
  - `brand/khatawas-master-icon.png` (Exact byte-for-byte 192x192 master)
  - `brand/khatawas-master-icon-transparent.png` (Transparent squircle corners variant)
  - `brand/web/` (`favicon.ico`, `favicon-16x16.png`, `favicon-32x32.png`, `apple-touch-icon.png`, `khatawas-icon-512.png`, `site.webmanifest`)
  - `brand/android/mipmap-nodpi/ic_launcher.png`
  - `brand/BRAND_IDENTITY_GUIDELINES.md`
  - `brand/sync-brand-assets.py` (Automated synchronization script)
- **Web Touchpoints**:
  - Favicon & Browser Tab: Multi-resolution `favicon.ico` + PNG icons
  - Navigation Header: Unified `<img src="/assets/khatawas-icon.png" class="brand-icon">` on `/` and `/app`
  - Hero Opening Screen: Displayed prominently as the master brand emblem in Act 1
- **Android Touchpoints**:
  - System Launcher: `android:icon="@mipmap/ic_launcher"`
  - In-App Action Bar: `ImageView` with `@mipmap/ic_launcher` in `activity_main.xml`
"""

if '### 2.3 Unified Brand Icon' not in content:
    target = '## 3. Storytelling Narrative Structure'
    content = content.replace(target, unified_brand_section + '\n---\n\n' + target)
    with open(path1, 'w', encoding='utf-8') as f:
        f.write(content)
    with open(path2, 'w', encoding='utf-8') as f:
        f.write(content)
    print('Updated documentation in both locations.')
else:
    print('Already updated.')
