import json, os, sys, requests, time

TOKEN = os.environ.get("GITHUB_TOKEN", "")
REPO = sys.argv[1]
APP_NAME = sys.argv[2]

HEADERS = {"Authorization": f"token {TOKEN}", "Accept": "application/vnd.github.v3+json"}

def safe_request(method, url, **kwargs):
    for attempt in range(5):
        try:
            resp = getattr(requests, method)(url, **kwargs)
            return resp
        except (requests.exceptions.ConnectionError, requests.exceptions.Timeout):
            print(f"  ⚠️ Network error, retry {attempt+1}/5...")
            time.sleep(3)
    return None

print(f"Fetching releases for {REPO}...")
r = safe_request("get", f"https://api.github.com/repos/qtgf520/{REPO}/releases?per_page=100", headers=HEADERS)
releases = r.json()

for rel in releases:
    tag = rel['tag_name']
    rid = rel['id']
    assets = rel.get('assets', [])
    if not assets:
        continue
    
    version = tag.lstrip('v')
    new_name = f"{APP_NAME}-{version}-android.apk"
    
    for asset in assets:
        old_name = asset['name']
        if old_name == new_name:
            print(f"✓ {tag}: already correct: {old_name}")
            continue
            
        aid = asset['id']
        dl_url = asset['browser_download_url']
        print(f"→ {tag}: {old_name} → {new_name}")
        
        dl_resp = safe_request("get", dl_url, headers=HEADERS)
        if dl_resp is None or dl_resp.status_code != 200:
            print(f"  ✗ Download failed")
            continue
        
        apk_data = dl_resp.content
        print(f"  Downloaded {len(apk_data)} bytes")
        
        del_resp = safe_request("delete", f"https://api.github.com/repos/qtgf520/{REPO}/releases/assets/{aid}", headers=HEADERS)
        if del_resp is None or del_resp.status_code not in (204, 404):
            print(f"  ✗ Delete failed")
            continue
        print(f"  ✓ Deleted old asset")
        
        upload_url = f"https://uploads.github.com/repos/qtgf520/{REPO}/releases/{rid}/assets?name={new_name}"
        upload_headers = {
            "Authorization": f"token {TOKEN}",
            "Content-Type": "application/vnd.android.package-archive"
        }
        up_resp = safe_request("post", upload_url, headers=upload_headers, data=apk_data)
        if up_resp is not None and up_resp.status_code in (200, 201):
            print(f"  ✓ Uploaded as {new_name}")
        else:
            print(f"  ✗ Upload failed: {up_resp.status_code if up_resp else 'None'}")
        
        time.sleep(1)

print("\nDone!")