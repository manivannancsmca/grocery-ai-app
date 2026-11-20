#!/usr/bin/env python3
"""Prints the build facts of one module as JSON: version, artifactId and the grocery library versions it pins.

    module-info.py <module dir>      (a Maven module with pom.xml, or an npm package with package.json)

Used by the grocery Jenkins shared library (image tags, schema-init version); standard library only.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def maven(path):
    root = ET.parse(path).getroot()

    def text(node, tag):
        el = node.find("m:" + tag, NS)
        return el.text.strip() if el is not None and el.text else None

    props = {}
    pnode = root.find("m:properties", NS)
    if pnode is not None:
        for p in pnode:
            props[p.tag.split("}")[-1]] = (p.text or "").strip()

    def resolve(value):
        if value is None:
            return None
        return re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m.group(1), m.group(0)), value)

    deps = {}
    for d in root.findall("m:dependencies/m:dependency", NS):
        if text(d, "groupId") == "com.grocery":
            deps[text(d, "artifactId")] = resolve(text(d, "version"))
    parent = root.find("m:parent", NS)
    return {
        "type": "maven",
        "groupId": text(root, "groupId") or (text(parent, "groupId") if parent is not None else None),
        "artifactId": text(root, "artifactId"),
        "version": resolve(text(root, "version")),
        "groceryCommon": deps.get("grocery-common"),
        "eventSchemas": deps.get("grocery-event-schemas"),
    }


def npm(path):
    data = json.load(open(path, encoding="utf-8"))
    return {"type": "npm", "artifactId": data.get("name"), "version": data.get("version")}


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: module-info.py <module dir>")
    d = sys.argv[1]
    if os.path.exists(os.path.join(d, "pom.xml")):
        info = maven(os.path.join(d, "pom.xml"))
    elif os.path.exists(os.path.join(d, "package.json")):
        info = npm(os.path.join(d, "package.json"))
    else:
        sys.exit("no pom.xml or package.json in " + d)
    if not info.get("version") or not re.match(r"^[0-9]+\.[0-9]+\.[0-9]+", info["version"]):
        sys.exit("cannot read a semantic version from %s (got %r)" % (d, info.get("version")))
    # Image tags are <semver>-<shortSha>: a Maven -SNAPSHOT suffix is dropped (the sha already makes it unique).
    info["semver"] = re.sub(r"-SNAPSHOT$", "", info["version"])
    print(json.dumps(info))


if __name__ == "__main__":
    main()
