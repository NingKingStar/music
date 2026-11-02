"""从真实签名 APK 生成客户端更新清单；不上传、不发布。"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def generate(version, code, apks, aapt2):
    if not re.fullmatch(r"[0-9]+(?:[.][0-9]+){2,3}", version) or code <= 0:
        raise ValueError("正式版本或版本码无效")
    packages = []
    for apk in apks:
        info = subprocess.check_output([str(aapt2), "dump", "badging", str(apk)], encoding="utf-8")
        match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", info)
        if not match or int(match[2]) != code or match[3] != version:
            raise ValueError("安装包版本与发布配置不符：" + str(apk))
        package = match[1]
        suffix = {"com.muses.player": "", "com.miui.player": "-mi"}.get(package)
        if suffix is None or apk.name != f"muses-v{version}{suffix}.apk":
            raise ValueError("包名或附件命名不符：" + str(apk))
        with apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        packages.append(dict(packageName=package, assetName=apk.name, sha256=digest))
    if not packages or len({p["packageName"] for p in packages}) != len(packages):
        raise ValueError("附件缺失或渠道重复")
    return dict(versionName=version, versionCode=code, packages=packages)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--aapt2", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("update.json"))
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()
    data = generate(args.version, args.code, args.apks, args.aapt2)
    args.output.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
