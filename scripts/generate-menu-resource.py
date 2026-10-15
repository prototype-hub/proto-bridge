#!/usr/bin/env python3
"""Convert an explicitly supplied legacy menu export to the current backend contract.

Usage: python3 scripts/generate-menu-resource.py INPUT_JSON OUTPUT_JSON
No default paths, database connection or startup writes are performed by this script.
"""
import argparse
from copy import deepcopy
import hashlib
import json
import re
from pathlib import Path

ALIASES = {"system_config": "system-config", "role-group": "role"}
UNSUPPORTED = {"device-product", "device-instance", "device-category", "assets-bind"}
ACTIONS = {
    "prototype-hub-project": {"query", "save", "delete"},
    "prototype-hub-version": {"query", "save", "delete"},
    "prototype-hub-share": {"query", "save", "delete"},
    "system-config": {"query", "save", "delete", "grant"},
    "user": {"query", "save", "delete", "grant", "update-self-info", "update-self-pwd"},
    "role": {"query", "save", "delete", "grant"},
    "menu": {"query", "save", "delete", "grant"},
    "permission": {"query", "save", "delete", "grant"},
    "dictionary": {"query", "save", "delete", "grant"},
    "organization": {"query", "save", "delete", "grant"},
    "position": {"query", "save", "delete", "grant"},
    "file": {"query", "save", "delete", "grant"},
    "access-logger": {"query"},
    "system-logger": {"query", "save"},
}
PRIMARY = {
    "prototype-hub": [], "prototype-hub/Project": [("prototype-hub-project", ["query"])],
    "system": [], "system/Basis": [("system-config", ["query"])],
    "system/User": [("user", ["query"])],
    "system/Department": [("organization", ["query"]), ("user", ["query"])],
    "system/Role": [("role", ["query"]), ("menu", ["query"])],
    "system/Menu": [("menu", ["query"]), ("permission", ["query"])],
    "system/Permission": [("permission", ["query"])],
    "system/Dictionary": [("dictionary", ["query"])],
    "system/Positions": [("position", ["query"]), ("organization", ["query"])],
    "system/Log": [], "system/Log/Access": [("access-logger", ["query"])],
    "system/Log/System": [("system-logger", ["query"])], "account-center": [],
}
# Only the packaged default menu codes and their known buttons receive operation metadata.
# The first PRIMARY resource owns writes; auxiliary page-query resources do not inherit them.
DEFAULT_BUTTON_ACTIONS = {
    "system/Basis": {"update": "save"},
    "system/User": {"add": "save", "update": "save", "delete": "delete", "action": "save"},
    "system/Department": {"add": "save", "update": "save", "delete": "delete", "bind": "save", "bind-user": "save"},
    "system/Role": {"add": "save", "update": "save", "delete": "delete", "groupUpdate": "save", "groupDelete": "delete"},
    "system/Menu": {"add": "save", "update": "save", "delete": "delete", "grant": "grant", "import": "save", "export": "query"},
    "system/Permission": {"add": "save", "update": "save", "delete": "delete", "import": "save", "export": "query"},
    "system/Dictionary": {"add": "save", "update": "save", "delete": "delete", "import": "save", "down": "query"},
    "system/Positions": {"add": "save", "update": "save", "delete": "delete", "bind": "save"},
    "system/Log/System": {"update": "save"},
}
# The position member UI writes through OrganizationController and queries user details.
# Keep this explicit endpoint dependency confined to the default position binding button.
DEFAULT_BUTTON_DEPENDENCIES = {
    ("system/Positions", "bind"): [("organization", ["query", "save"]), ("user", ["query"])],
}

# Menu codes are stable frontend routes; language labels do not participate in authorization.
MENU_ENGLISH = {
    "prototype-hub": "Prototype Management",
    "prototype-hub/Project": "Prototype Projects",
    "system": "System Management",
    "system/Basis": "Basic Configuration",
    "system/User": "User Management",
    "system/Department": "Organization Management",
    "system/Role": "Role Management",
    "system/Menu": "Menu Management",
    "system/Permission": "Permission Management",
    "system/Dictionary": "Data Dictionary",
    "system/Positions": "Position Management",
    "system/Log": "Log Management",
    "system/Log/Access": "Access Logs",
    "system/Log/System": "System Logs",
    "account-center": "User Center",
}
BUTTON_NAMES = {
    "add": ("新增", "Add"),
    "update": ("编辑", "Edit"),
    "delete": ("删除", "Delete"),
    "action": ("操作", "Actions"),
    "bind": ("绑定/解绑成员", "Bind/Unbind Members"),
    "bind-user": ("绑定/解绑成员", "Bind/Unbind Members"),
    "groupUpdate": ("保存角色分组", "Save Role Group"),
    "groupDelete": ("删除角色分组", "Delete Role Group"),
    "grant": ("授权", "Grant"),
    "import": ("导入", "Import"),
    "export": ("导出", "Export"),
    "down": ("下载", "Download"),
    "user-center-passwd-update": ("修改密码", "Change Password"),
}


PRESERVED = ("id", "parentId", "name", "code", "url", "icon", "sortIndex", "describe",
             "assetType", "indirectMenus", "options", "i18nMessages")
ID = re.compile(r"[A-Za-z0-9_-]{1,64}")


OPAQUE_FIELDS = {"id", "parentId", "name", "label", "description", "describe", "hint", "action", "actions", "indirectMenus"}
PERMISSION_ALIASES = {f"protohub-{kind}": f"prototype-hub-{kind}" for kind in ("project", "version", "share")}


def normalize_reference(value, context):
    if context in OPAQUE_FIELDS or re.fullmatch(r"[a-z]{2}(?:[_-][A-Za-z]{2})?", context):
        return value
    if context in {"permission", "permissionId", "resource", "resourceId", "permissions", "permissionIds", "resources", "parents", "managedPermissions", "metadata-key"} and value in PERMISSION_ALIASES:
        return PERMISSION_ALIASES[value]
    if context in {"code", "componentCode", "parentCode", "menuCode", "code-key"} and (value == "protohub" or value.startswith("protohub/")):
        return "prototype-hub" + value[8:]
    if context in {"url", "route", "redirect", "redirectTo", "path", "route-key"} and re.match(r"^/protohub(?:$|[/?#])", value):
        return "/prototype-hub" + value[9:]
    if context in {"metadata-key", "i18n", "i18nKey", "messageKey", "nameKey", "titleKey", "localeKey"}:
        if re.match(r"^(protohub|protoHub)\.", value):
            return "prototypeHub" + value[8:]
        if value.startswith("error.protohub."):
            return "error.prototype-hub." + value[15:]
        prefix = "hswebframework.web.system.permission."
        if value.startswith(prefix) and value[len(prefix):] in PERMISSION_ALIASES:
            return prefix + PERMISSION_ALIASES[value[len(prefix):]]
    return value


def normalize_references(value, context=""):
    """Rename technical references without modifying labels, IDs or arbitrary external URLs."""
    if isinstance(value, dict):
        result = {}
        key_context = "code-key" if context in {"codes", "codeMap"} else "route-key" if context in {"routes", "routeMap"} else "metadata-key"
        for key, item in value.items():
            renamed = normalize_reference(key, key_context)
            if renamed in result:
                raise ValueError(f"PrototypeHub JSON key conflict: {renamed}")
            if key == "id" and context in {"permissions", "resources", "permissionIds"} and isinstance(item, str):
                result[renamed] = normalize_reference(item, "permission")
            else:
                result[renamed] = deepcopy(item) if key in OPAQUE_FIELDS else normalize_references(item, key)
        return result
    if isinstance(value, list):
        return [normalize_references(item, context) for item in value]
    return normalize_reference(value, context) if isinstance(value, str) else value


def explicit(values):
    return [{"permission": permission, "actions": actions} for permission, actions in values]


def permissions(values):
    if not isinstance(values, list):
        raise ValueError("permissions must be an array")
    merged = {}
    for entry in values:
        if not isinstance(entry, dict):
            raise ValueError("permission entry must be an object")
        resource = entry.get("permission", entry.get("id"))
        actions = entry.get("actions")
        if not isinstance(resource, str) or not isinstance(actions, list):
            raise ValueError("invalid permission entry")
        if resource in UNSUPPORTED:
            continue  # Explicitly excluded IoT capabilities; unknown resources still fail.
        resource = PERMISSION_ALIASES.get(resource, ALIASES.get(resource, resource))
        if resource not in ACTIONS:
            raise ValueError(f"unknown permission: {resource}")
        for action in actions:
            if resource == "file" and action == "upload-static":
                action = "save"
            if resource == "organization" and action in {"bind-user", "unbind-user"}:
                action = "save"
            if action not in ACTIONS[resource]:
                raise ValueError(f"unknown permission action: {resource}:{action}")
            if action not in merged.setdefault(resource, []):
                merged[resource].append(action)
    return explicit(list(merged.items()))


def nonblank(value):
    return isinstance(value, str) and bool(value.strip())


def translations(values, zh, en):
    """Fill missing labels without overwriting an export's explicit translations."""
    if values is None:
        values = {}
    if not isinstance(values, dict):
        raise ValueError("i18n translations must be an object")
    result = deepcopy(values)
    for locale, default in [("zh_CN", zh), ("en_US", en), ("en", en)]:
        if not nonblank(result.get(locale)):
            result[locale] = default
    return result


def localize_menu(row):
    messages = deepcopy(row.get("i18nMessages") or {})
    if not isinstance(messages, dict):
        raise ValueError("menu i18nMessages must be an object")
    names = messages.get("name") or {}
    if not isinstance(names, dict):
        raise ValueError("menu name translations must be an object")
    name = names.get("zh_CN") if nonblank(names.get("zh_CN")) else row["name"]
    if not 1 <= len(name) <= 128:
        raise ValueError("invalid localized menu name")
    row["name"] = name
    messages["name"] = translations(names, name, MENU_ENGLISH[row["code"]])
    row["i18nMessages"] = messages


def localize_button(row, code):
    # Buttons use the existing flat locale map; menus use field -> locale maps.
    token = row["id"]
    name = row.get("name", token)
    zh, en = BUTTON_NAMES.get(token, (name, name))
    if code == "system/Log/System" and token == "update":
        zh, en = "调整日志级别", "Update Log Level"
    messages = row.get("i18nMessages") or {}
    if not isinstance(messages, dict):
        raise ValueError("button i18nMessages must be an object")
    if nonblank(messages.get("zh_CN")):
        name = messages["zh_CN"]
    elif name in {token, zh, en}:
        name = zh
    row["name"] = name
    row["i18nMessages"] = translations(messages, name, en)


def button(identifier, zh, en, values):
    return {"id": identifier, "name": zh, "permissions": explicit(values),
            "i18nMessages": {"zh_CN": zh, "en_US": en, "en": en}}


def complete_default_button_permissions(current, code):
    """Complete known seed metadata without adding runtime or custom-button inference."""
    action = DEFAULT_BUTTON_ACTIONS.get(code, {}).get(current["id"])
    if action is None:
        return
    primary = PRIMARY[code][0][0]
    current["permissions"] = permissions(
        current["permissions"]
        + explicit([(primary, [action])])
        + explicit(DEFAULT_BUTTON_DEPENDENCIES.get((code, current["id"]), [])))


def convert(source):
    if not isinstance(source, list) or not source:
        raise ValueError("menu roots must be a nonempty array")
    rows, codes = {}, set()

    def walk(values, parent=None, depth=1):
        if depth > 20 or not isinstance(values, list):
            raise ValueError("invalid menu tree depth or children")
        result = []
        for original in values:
            if not isinstance(original, dict):
                raise ValueError("menu must be an object")
            original = normalize_references(original)
            identifier, code = original.get("id"), original.get("code")
            if not isinstance(identifier, str) or not ID.fullmatch(identifier) or identifier in rows:
                raise ValueError("invalid or duplicate menu ID")
            if code not in PRIMARY or code in codes:
                raise ValueError(f"unsupported or duplicate menu code: {code}")
            if len(rows) >= 500:
                raise ValueError("menu node limit exceeded")
            if original.get("parentId") not in (None, "", parent):
                raise ValueError("parentId conflicts with the exported tree")
            if not isinstance(original.get("name"), str) or not 1 <= len(original["name"]) <= 128:
                raise ValueError("invalid menu name")
            permissions(original.get("permissions", []))  # Validate even overridden leaf requirements.
            row = {key: original[key] for key in PRESERVED if key in original}
            if parent is None:
                row.pop("parentId", None)
            else:
                row["parentId"] = parent
            localize_menu(row)
            row["owner"] = "proto"  # Match the packaged startup owner; existing database owners are never rewritten.
            status = original.get("status", 1)
            if status not in (0, 1):
                raise ValueError("status must be zero or one")
            row["enabled"] = status == 1
            support = original.get("accessSupport", "unsupported")
            support = support.get("value") if isinstance(support, dict) else support
            if support not in {"unsupported", "support", "indirect"}:
                raise ValueError("invalid accessSupport")
            row["accessSupport"] = support
            if support == "support" and not row.get("assetType"):
                raise ValueError("support requires an assetType descriptor")
            row["componentCode"] = original.get("options", {}).get("meta", {}).get("componentCode") or code
            row["permissions"] = explicit(PRIMARY[code])
            rows[identifier], _ = row, codes.add(code)
            buttons, seen = [], set()
            for old in original.get("buttons", []):
                token = old.get("id")
                if not isinstance(token, str) or not ID.fullmatch(token) or token in seen:
                    raise ValueError("invalid or duplicate button ID")
                seen.add(token)
                normalized = permissions(old.get("permissions", []))
                if code == "system/Department" and token == "assert":
                    continue  # Pure asset allocation has no current backend implementation.
                current = {k: v for k, v in old.items() if k not in {"granted", "i18nName"}}
                current["permissions"] = normalized
                if code == "system/Department" and token in {"bind", "bind-user"}:
                    current["permissions"] = explicit(
                        [("organization", ["query", "save"]), ("user", ["query"])])
                if code == "system/Menu" and token in {"add", "update", "delete"}:
                    for permission in current["permissions"]:
                        if permission["permission"] == "menu":
                            permission["actions"] = [a for a in permission["actions"] if a != "grant"]
                if code == "account-center" and token == "user-center-passwd-update":
                    current["permissions"] = explicit([("user", ["update-self-pwd"])])
                buttons.append(current)
            additions = []
            if code == "prototype-hub/Project":
                for token, zh, en, action in [("add", "新增", "Add", "save"),
                                               ("update", "编辑", "Edit", "save"),
                                               ("delete", "删除", "Delete", "delete")]:
                    additions.append(button(token, zh, en, [(r, [action]) for r in
                                     ["prototype-hub-project", "prototype-hub-version", "prototype-hub-share"]]))
            if code == "system/Role":
                additions = [button("groupUpdate", "保存角色分组", "Save Role Group", [("role", ["save"])]),
                             button("groupDelete", "删除角色分组", "Delete Role Group", [("role", ["delete"])])]
            if code == "system/Menu":
                additions = [button("import", "导入", "Import", [("menu", ["save"])]),
                             button("export", "导出", "Export", [("menu", ["query"])]),
                             button("grant", "授权", "Grant", [("menu", ["query", "grant"])])]
            if code == "system/Positions":
                additions = [button("bind", "绑定/解绑成员", "Bind/Unbind Members",
                                    [("organization", ["query", "save"]), ("user", ["query"])])]
            if code == "system/Log/System":
                additions = [button("update", "调整日志级别", "Update Log Level", [("system-logger", ["save"])])]
            buttons.extend(item for item in additions if item["id"] not in seen)
            for current in buttons:
                complete_default_button_permissions(current, code)
                localize_button(current, code)
            row["buttons"] = buttons
            if "children" in original:
                row["children"] = walk(original["children"], identifier, depth + 1)
            result.append(row)
        return result

    output = walk(source)
    for row in rows.values():
        refs = row.get("indirectMenus", [])
        if not isinstance(refs, list) or len(refs) > 200 or any(ref not in rows for ref in refs):
            raise ValueError("invalid indirect menu reference")
        if row["accessSupport"] == "indirect" and not refs:
            raise ValueError("indirect requires referenced menus")

    def references(identifier, path, depth=0):
        if depth > 20 or identifier in path:
            raise ValueError("indirect menu cycle or excessive depth")
        for target in rows[identifier].get("indirectMenus", []):
            references(target, path | {identifier}, depth + 1)
    for identifier in rows:
        references(identifier, set())
    return output, len(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error("input and output must be different files")
    raw = args.input.read_bytes()
    if len(raw) > 1024 * 1024:
        parser.error("input exceeds 1 MiB")
    menus, count = convert(json.loads(raw))
    encoded = (json.dumps(menus, ensure_ascii=False, indent=2) + "\n").encode()
    if len(encoded) > 1024 * 1024:
        parser.error("output exceeds 1 MiB")
    args.output.write_bytes(encoded)
    print(f"nodes={count}; roots={len(menus)}; input_sha256={hashlib.sha256(raw).hexdigest()}; output_sha256={hashlib.sha256(encoded).hexdigest()}")

if __name__ == "__main__":
    main()
