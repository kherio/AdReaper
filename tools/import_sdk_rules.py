#!/usr/bin/env python3
"""Merge ad-SDK package prefixes into assets/rules/app_rules.json ("*" -> kill_activities).

Source: https://github.com/lihenggui/blocker-general-rules (Apache-2.0), src/rules/*.yaml
for the package prefixes and upstream/exodus/trackers.json only to tell advertising SDKs
from analytics ones. Only prefixes of SDKs classified as pure advertising are imported,
plus a short list of ad networks that also ship analytics.

Usage: tools/import_sdk_rules.py /path/to/blocker-general-rules [--dry-run]
Idempotent: re-running only adds prefixes that are missing.
"""
import glob, json, os, sys
import yaml

RULES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "rules", "app_rules.json")

# Ad networks that are not "advertisement only" upstream but are plain ad SDKs.
EXTRA_ADS = {"Mintegral", "MoPub Ads", "Startapp", "AppLovin (MAX and SparkLabs)", "Appodeal Stack", "MobFox"}
# Classified as advertising upstream but are not ad SDKs / too broad to kill activities by prefix.
EXCLUDE = {"Prebid Mobile", "Moodmedia", "CallDorado", "Admitad", "Carto (formerly Nutiteq)"}
# Prefixes that also cover the host apps' own code (e.g. Alibaba's shopping apps).
EXCLUDE_PREFIXES = {"com.alimama."}


def main():
    src = sys.argv[1]
    dry = "--dry-run" in sys.argv
    trackers = {e["id"]: e for e in json.load(open(os.path.join(src, "upstream/exodus/trackers.json")))}

    prefixes = set()
    for path in sorted(glob.glob(os.path.join(src, "src/rules/*.yaml"))):
        rule = yaml.safe_load(open(path))
        name = rule["i18n"]["en"].get("name", "")
        exodus = (rule.get("upstream") or {}).get("exodus")
        cats = trackers.get(exodus, {}).get("categories", [])
        pure_ad = cats == ["Advertisement"]
        if name in EXCLUDE or not (pure_ad or name in EXTRA_ADS):
            continue
        for kw in rule.get("keywords", []):
            kw = kw.strip()
            # package-like, specific enough, not a regex or a single class of another SDK
            if len(kw) < 8 or kw.startswith(".") or kw.count(".") < 2 or any(c in kw for c in "*\\[]()|"):
                continue
            if kw not in EXCLUDE_PREFIXES:
                prefixes.add(kw)

    data = json.load(open(RULES))
    glob_rules = data["apps"]["*"]
    existing = glob_rules.setdefault("kill_activities", [])
    added = 0
    for p in sorted(prefixes):
        if not any(p.startswith(e) or e.startswith(p) for e in existing):
            existing.append(p)
            added += 1
    print(f"{len(prefixes)} candidate prefixes, {added} added, {len(existing)} kill_activities total")
    if not dry:
        with open(RULES, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
