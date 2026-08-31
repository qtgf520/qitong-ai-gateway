import json, sys, os

repo = sys.argv[1]
token = os.environ.get("GITHUB_TOKEN", "")
os.system(f'curl -s -H "Authorization: token {token}" "https://api.github.com/repos/qtgf520/{repo}/releases?per_page=100" > /tmp/releases.json')

with open("/tmp/releases.json") as f:
    data = json.load(f)

for rel in data:
    rid = rel['id']
    tag = rel['tag_name']
    assets_list = rel.get('assets', [])
    if assets_list:
        for a in assets_list:
            print(f"{rid} | {tag} | {a['id']} | {a['name']} | {a['state']} | {a['browser_download_url']}")
    else:
        print(f"{rid} | {tag} | (no assets)")