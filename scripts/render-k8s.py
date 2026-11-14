#!/usr/bin/env python3
"""Render every k8s/ folder of a cluster exactly like Flux: kustomize build, then strict ${VAR}
substitution per resource from site-settings.yaml (skipping resources annotated
kustomize.toolkit.fluxcd.io/substitute: disabled). Writes the result to stdout.

Usage: scripts/render-k8s.py [cluster] > rendered.yaml       (needs kubectl and the flux CLI)
Exit 1 if a folder does not build or a variable is unknown (Flux runs in strict mode).
"""
import os, subprocess, sys, pathlib
import yaml

root = pathlib.Path(__file__).resolve().parent.parent
cluster = sys.argv[1] if len(sys.argv) > 1 else "homelab"
env = dict(os.environ)
env.update(yaml.safe_load((root / f"clusters/{cluster}/site-settings.yaml").read_text())["data"])
paths = [d["spec"]["path"] for d in yaml.safe_load_all((root / f"clusters/{cluster}/platform.yaml").read_text())
         if d and d.get("kind") == "Kustomization"]
failed = False
for path in paths:
    build = subprocess.run(["kubectl", "kustomize", str(root / path)], capture_output=True, text=True)
    if build.returncode:
        print(f"{path}: kustomize build failed:\n{build.stderr}", file=sys.stderr); failed = True; continue
    count = 0
    for doc in yaml.safe_load_all(build.stdout):
        if not doc:
            continue
        ann = (doc.get("metadata") or {}).get("annotations") or {}
        text = yaml.safe_dump(doc, sort_keys=True)
        if ann.get("kustomize.toolkit.fluxcd.io/substitute") != "disabled":
            sub = subprocess.run(["flux", "envsubst", "--strict"], input=text, capture_output=True, text=True, env=env)
            if sub.returncode:
                name = f"{doc.get('kind')}/{(doc.get('metadata') or {}).get('name')}"
                print(f"{path}: {name}: {sub.stderr.strip()}", file=sys.stderr); failed = True; continue
            text = sub.stdout
        print("---"); print(text, end=""); count += 1
    print(f"{path}: {count} resources", file=sys.stderr)
sys.exit(1 if failed else 0)
