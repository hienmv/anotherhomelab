#!/usr/bin/env python3
"""Validate the site configuration of one cluster (CI job `validate`, also runnable locally).

Usage: scripts/validate-config.py [cluster]        (default: homelab)
In GitHub Actions it also checks the values against the repository itself
(GITHUB_REPOSITORY, GITHUB_REPOSITORY_OWNER). Exit 1 with one line per problem.
"""
import os, re, sys, pathlib
import yaml

root = pathlib.Path(__file__).resolve().parent.parent
cluster = sys.argv[1] if len(sys.argv) > 1 else "homelab"
problems = []

def load(path):
    try:
        return yaml.safe_load((root / path).read_text())
    except Exception as e:  # noqa: BLE001 - report, don't crash
        problems.append(f"{path}: cannot read ({e})")
        return {}

settings_path = f"clusters/{cluster}/site-settings.yaml"
data = (load(settings_path) or {}).get("data") or {}

# key -> (regex, description)
schema = {
    "CLUSTER_NAME": (r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", "lowercase DNS label"),
    "DOMAIN": (r"^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$", "lowercase domain, e.g. example.com"),
    "GIT_REPO_SLUG": (r"^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$", "owner/repo"),
    "GIT_REPO_SSH": (r"^git@github\.com:[A-Za-z0-9-]+/[A-Za-z0-9._-]+\.git$", "git@github.com:owner/repo.git"),
    "IMAGE_REGISTRY": (r"^ghcr\.io/[a-z0-9-]+$", "ghcr.io/<lowercase owner>"),
    "CLOUDFLARE_TUNNEL_ID": (r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", "UUID"),
    "TIMEZONE": (r"^[A-Za-z_]+(/[A-Za-z0-9_+-]+)*$", "IANA zone, e.g. Asia/Tokyo"),
}
for key, (rx, desc) in schema.items():
    value = str(data.get(key, ""))
    if not value:
        problems.append(f"{settings_path}: {key} is missing")
    elif not re.match(rx, value):
        problems.append(f"{settings_path}: {key}={value!r} is not a {desc}")
for key in sorted(set(data) - set(schema)):
    problems.append(f"{settings_path}: unknown key {key} (add it to the schema in scripts/validate-config.py)")

# consistency between the values
if data.get("CLUSTER_NAME") and data["CLUSTER_NAME"] != cluster:
    problems.append(f"{settings_path}: CLUSTER_NAME={data['CLUSTER_NAME']} but the folder is clusters/{cluster}")
slug = data.get("GIT_REPO_SLUG", "")
if slug and data.get("GIT_REPO_SSH") and data["GIT_REPO_SSH"] != f"git@github.com:{slug}.git":
    problems.append(f"{settings_path}: GIT_REPO_SSH does not match GIT_REPO_SLUG={slug}")
tz = data.get("TIMEZONE")
if tz and pathlib.Path("/usr/share/zoneinfo").is_dir() and not (pathlib.Path("/usr/share/zoneinfo") / tz).is_file():
    problems.append(f"{settings_path}: TIMEZONE={tz} is not a known time zone")

# Flux bootstrap: the only place the Git URL is written out (read before any variable exists)
sync_path = f"clusters/{cluster}/flux-system/gotk-sync.yaml"
for doc in yaml.safe_load_all((root / sync_path).read_text()) if (root / sync_path).exists() else []:
    if doc and doc.get("kind") == "GitRepository":
        url = doc.get("spec", {}).get("url", "")
        if slug and url.lower() != f"ssh://git@github.com/{slug}.git".lower():
            problems.append(f"{sync_path}: url {url} != ssh://git@github.com/{slug}.git (GIT_REPO_SLUG)")

# against the repository itself (only in GitHub Actions)
repo, owner = os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_REPOSITORY_OWNER")
if repo and slug and repo.lower() != slug.lower():
    problems.append(f"{settings_path}: GIT_REPO_SLUG={slug} but this repository is {repo}")
if owner and data.get("IMAGE_REGISTRY") and data["IMAGE_REGISTRY"] != f"ghcr.io/{owner.lower()}":
    problems.append(f"{settings_path}: IMAGE_REGISTRY={data['IMAGE_REGISTRY']} but CI pushes to ghcr.io/{owner.lower()}")

# every Kustomization in platform.yaml substitutes from site-settings
platform = f"clusters/{cluster}/platform.yaml"
for doc in yaml.safe_load_all((root / platform).read_text()):
    if doc and doc.get("kind") == "Kustomization":
        sf = (doc.get("spec", {}).get("postBuild") or {}).get("substituteFrom") or []
        if not any(s.get("name") == "site-settings" for s in sf):
            problems.append(f"{platform}: Kustomization {doc['metadata']['name']} has no substituteFrom site-settings")

for p in problems:
    print(f"::error::{p}" if os.environ.get("GITHUB_ACTIONS") else f"PROBLEM: {p}")
print(f"{'FAILED' if problems else 'OK'}: {settings_path} ({len(data)} keys), {sync_path}, {platform}")
sys.exit(1 if problems else 0)
