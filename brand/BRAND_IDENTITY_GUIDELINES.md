# KHATAWAS — Master Brand Identity & Asset Guidelines

## 1. Source of Truth
The KHATAWAS brand identity across both the Android application and Website originates from the canonical Android launcher icon:

- **Canonical Master Asset**: `app/src/main/res/mipmap-nodpi/ic_launcher.png`
- **Shared Master Copy**: `brand/khatawas-master-icon.png`
- **Master Checksum (SHA-256)**: `7728775ff9b56520786b70806c750e9db5a76f2c985b15ae0530c2d3b8f5ad35`
- **Dimensions**: `192 x 192 px`
- **Format**: PNG (8-bit palette / RGBA)

---

## 2. Visual Identity & Artwork Rules

1. **Artwork Integrity**:
   - The original shape, colors, proportions, and visual identity are preserved across all platforms.
   - No independent redrawing, recoloring, distortion, or artificial re-interpretation is permitted.
   - The central emblem features the traditional bahi-khata ledger book and voice rupee accounting motif framed within a rich emerald squircle container.

2. **Cross-Platform Consistency**:
   - **Android App**:
     - System launcher: `app/src/main/res/mipmap-nodpi/ic_launcher.png`
     - In-app app bar header: In `activity_main.xml`, the brand icon (`@mipmap/ic_launcher`) appears directly alongside the app title `@string/app_name`.
   - **Web Application**:
     - Navigation header: Displayed as `<img src="/assets/khatawas-icon.png" class="brand-icon">` on both the landing page (`/`) and web ledger application (`/app`).
     - Hero opening story: Prominently featured as the brand emblem at the entrance of Act 1.
     - Browser Favicon & Tab: Powered by multi-resolution `favicon.ico`, `favicon-16x16.png`, `favicon-32x32.png`, and `apple-touch-icon.png` (180x180).
     - PWA Manifest: Referenced in `site.webmanifest` (192x192 and 512x512 maskable icons).

3. **Background Adaptability**:
   - For standard square/squircle contexts (Android launcher, app bar, web navigation): The master 192x192 icon is used.
   - For transparent embeds: A pixel-accurate transparent-corner variant (`khatawas-master-icon-transparent.png` and `public/assets/khatawas-icon-transparent.png`) where only the corner padding outside the squircle boundary is transparent (alpha = 0), keeping 100% of the internal artwork intact.

---

## 3. Asset Directory Hierarchy

```
brand/
├── BRAND_IDENTITY_GUIDELINES.md          # This documentation
├── khatawas-master-icon.png               # Exact byte-for-byte master asset (192x192)
├── khatawas-master-icon-transparent.png   # Transparent corner padding variant (192x192)
├── sync-brand-assets.py                   # Automated synchronization and verification script
├── android/
│   └── mipmap-nodpi/
│       └── ic_launcher.png                # Android launcher icon (192x192)
└── web/
    ├── favicon.ico                        # Multi-res (16x16, 32x32, 48x48)
    ├── favicon-16x16.png                  # Small browser tab icon (16x16)
    ├── favicon-32x32.png                  # Standard browser tab icon (32x32)
    ├── apple-touch-icon.png               # iOS home screen icon (180x180)
    ├── khatawas-icon-512.png              # High-DPI PWA splash icon (512x512)
    └── site.webmanifest                   # Web application manifest
```

---

## 4. Synchronization Protocol

Whenever the master icon is updated, run the automated sync script from either repository:

```bash
python brand/sync-brand-assets.py
```

The script performs:
1. Verification of the master asset's existence and integrity.
2. Direct propagation to Android `app/src/main/res/mipmap-nodpi/ic_launcher.png`.
3. Direct propagation to Website `public/assets/khatawas-icon.png`.
4. Deterministic generation of all derivative web assets (`favicon.ico`, `favicon-16x16.png`, `favicon-32x32.png`, `apple-touch-icon.png`, `khatawas-icon-512.png`).
5. Output validation across both local repositories (`bwb2` and `khatawas-site`).
