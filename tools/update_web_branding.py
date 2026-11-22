import re

# 1. Update index.html
index_path = r'D:\khatawas-site\public\index.html'
with open(index_path, 'r', encoding='utf-8') as f:
    idx_content = f.read()

# Update head favicons
old_favicon = '<link rel="icon" type="image/png" href="/assets/khatawas-icon.png">'
new_favicons = '''<link rel="icon" type="image/png" sizes="32x32" href="/assets/favicon-32x32.png">
  <link rel="icon" type="image/png" sizes="16x16" href="/assets/favicon-16x16.png">
  <link rel="icon" type="image/x-icon" href="/favicon.ico">
  <link rel="apple-touch-icon" sizes="180x180" href="/assets/apple-touch-icon.png">
  <link rel="manifest" href="/site.webmanifest">'''

if old_favicon in idx_content:
    idx_content = idx_content.replace(old_favicon, new_favicons, 1)

# Add hero brand emblem above hero-badge in Act 1
old_hero_badge = '''        <div class="hero-badge reveal">
          <span class="hero-badge-pulse" aria-hidden="true"></span>
          <span>Purpose-Built for Indian Kirana & Small Retail</span>
        </div>'''

new_hero_badge = '''        <div class="hero-brand-emblem reveal" aria-hidden="true">
          <img src="/assets/khatawas-icon.png" alt="KHATAWAS Brand" class="hero-brand-icon" width="64" height="64">
        </div>

        <div class="hero-badge reveal">
          <span class="hero-badge-pulse" aria-hidden="true"></span>
          <span>Purpose-Built for Indian Kirana & Small Retail</span>
        </div>'''

if old_hero_badge in idx_content:
    idx_content = idx_content.replace(old_hero_badge, new_hero_badge, 1)

with open(index_path, 'w', encoding='utf-8') as f:
    f.write(idx_content)
print("Updated index.html successfully.")

# 2. Update app.html
app_path = r'D:\khatawas-site\public\app.html'
with open(app_path, 'r', encoding='utf-8') as f:
    app_content = f.read()

# Replace inline SVG icon in app.html
app_content = re.sub(
    r'<link rel="icon" href="data:image/svg\+xml,[^"]+">',
    '''<link rel="icon" type="image/png" sizes="32x32" href="/assets/favicon-32x32.png">
<link rel="icon" type="image/png" sizes="16x16" href="/assets/favicon-16x16.png">
<link rel="icon" type="image/x-icon" href="/favicon.ico">
<link rel="apple-touch-icon" sizes="180x180" href="/assets/apple-touch-icon.png">
<link rel="manifest" href="/site.webmanifest">''',
    app_content
)

# Add brand logo in app.html header
old_app_header = '<div class="brand"><a href="/" style="color: inherit; text-decoration: none;">Khatawas</a><small id="sub">Voice ledger for your kirana store</small></div>'
new_app_header = '''<div class="brand" style="display: flex; align-items: center; gap: 0.75rem;">
    <a href="/" style="display: flex; align-items: center; text-decoration: none;">
      <img src="/assets/khatawas-icon.png" alt="KHATAWAS Brand" width="36" height="36" style="border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.25);">
    </a>
    <div>
      <a href="/" style="color: inherit; text-decoration: none;">Khatawas</a>
      <small id="sub" style="display: block;">Voice ledger for your kirana store</small>
    </div>
  </div>'''

if old_app_header in app_content:
    app_content = app_content.replace(old_app_header, new_app_header, 1)

with open(app_path, 'w', encoding='utf-8') as f:
    f.write(app_content)
print("Updated app.html successfully.")

# 3. Update landing.css for hero-brand-emblem
css_path = r'D:\khatawas-site\public\landing.css'
with open(css_path, 'r', encoding='utf-8') as f:
    css_content = f.read()

hero_emblem_css = """
/* Hero Brand Emblem */
.hero-brand-emblem {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 1.5rem;
  position: relative;
}

.hero-brand-icon {
  width: 68px;
  height: 68px;
  border-radius: 16px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.6), 0 0 30px rgba(245, 158, 11, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.12);
  transition: transform 0.4s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.4s ease;
}

.hero-brand-icon:hover {
  transform: scale(1.05);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.7), 0 0 45px rgba(245, 158, 11, 0.4);
}
"""

if '.hero-brand-emblem' not in css_content:
    css_content += hero_emblem_css
    with open(css_path, 'w', encoding='utf-8') as f:
        f.write(css_content)
    print("Updated landing.css successfully.")
