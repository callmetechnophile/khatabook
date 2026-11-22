import urllib.request

endpoints = [
    '/',
    '/app',
    '/healthz',
    '/api/config',
    '/assets/khatawas-icon.png',
    '/favicon.ico',
    '/landing.css',
    '/src/ledger.js',
    '/site.webmanifest'
]

base = 'https://khatawas.vercel.app'
print("Checking Vercel deployment endpoints...")
all_ok = True
for ep in endpoints:
    url = base + ep
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as res:
            ct = res.headers.get("Content-Type", "")
            data = res.read()
            print(f"{ep:<25} -> {res.status} OK ({len(data)} bytes, {ct})")
    except Exception as e:
        print(f"{ep:<25} -> ERROR: {e}")
        all_ok = False

if all_ok:
    print("\nAll endpoints returned 200 OK successfully!")
