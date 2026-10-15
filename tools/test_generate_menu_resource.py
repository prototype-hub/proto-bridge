"""Regression checks for the menu export's stored names and locale metadata."""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools/generate-menu-resource.py"
FIXTURE = ROOT / "tools/fixtures/menu-export.json"
PACKAGED = ROOT / "modules/system-manager/src/main/resources/menus/default-menus.json"
SPEC = importlib.util.spec_from_file_location("menu_resource", SCRIPT)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


def flatten(roots):
    rows = {}
    for row in roots:
        rows[row["id"]] = row
        rows.update(flatten(row.get("children", [])))
    return rows


class MenuResourceLanguageTest(unittest.TestCase):
    def setUp(self):
        self.source = json.loads(FIXTURE.read_text())

    def test_fixture_conversion_keeps_identity_and_persists_chinese_menu_and_button_names(self):
        original = deepcopy(self.source)
        roots, count = GENERATOR.convert(self.source)
        self.assertEqual(6, count)
        self.assertEqual(2, len(roots))
        self.assertEqual(original, self.source)
        source, actual = flatten(original), flatten(roots)
        self.assertEqual(source.keys(), actual.keys())
        self.assertNotIn("account-center", {row["code"] for row in actual.values()})
        buttons = 0
        for identifier, row in actual.items():
            old = source[identifier]
            for field in ["code", "url", "options"]:
                self.assertEqual(old[field], row[field])
            self.assertEqual(old.get("parentId"), row.get("parentId"))
            self.assertEqual("proto", row["owner"])
            self.assertEqual(row["name"], row["i18nMessages"]["name"]["zh_CN"])
            self.assertTrue(any("\u4e00" <= char <= "\u9fff" for char in row["name"]))
            for locale in ["en_US", "en"]:
                self.assertTrue(row["i18nMessages"]["name"][locale].strip())
            for button in row["buttons"]:
                buttons += 1
                self.assertEqual(button["name"], button["i18nMessages"]["zh_CN"])
                self.assertTrue(any("\u4e00" <= char <= "\u9fff" for char in button["name"]))
                self.assertTrue(button["i18nMessages"]["en_US"].strip())
                self.assertTrue(button["i18nMessages"]["en"].strip())
        self.assertEqual(5, buttons)
        self.assertEqual("基础配置", actual["system-basis"]["name"])
        self.assertEqual("数据字典", actual["system-dictionary"]["name"])

    def test_existing_chinese_and_english_translations_and_other_fields_are_preserved(self):
        row = flatten(self.source)["system-user"]
        row["name"] = "Users"
        row["i18nMessages"] = {"name": {"zh_CN": "成员管理", "en_US": "Custom Members", "en": "Members", "fr": "Membres"},
                               "describe": {"en": "Existing description"}}
        result = flatten(GENERATOR.convert(self.source)[0])["system-user"]
        self.assertEqual("成员管理", result["name"])
        self.assertEqual(row["i18nMessages"], result["i18nMessages"])

    def test_missing_alias_is_filled_without_overwriting_existing_english(self):
        row = flatten(self.source)["system-user"]
        row["i18nMessages"] = {"name": {"zh_CN": "用户管理", "en_US": "Existing English", "en": " "}}
        result = flatten(GENERATOR.convert(self.source)[0])["system-user"]
        self.assertEqual("Existing English", result["i18nMessages"]["name"]["en_US"])
        self.assertEqual("User Management", result["i18nMessages"]["name"]["en"])

    def test_button_translation_survives_permission_compatibility_normalization(self):
        row = flatten(self.source)["system-org"]
        button = next(item for item in row["buttons"] if item["id"] == "bind")
        button["i18nMessages"] = {"zh_CN": "成员关联", "en_US": "Associate Members", "en": "Associate", "fr": "Associer"}
        button["options"] = {"confirm": True}
        result = flatten(GENERATOR.convert(self.source)[0])["system-org"]
        actual = next(item for item in result["buttons"] if item["id"] == "bind")
        self.assertEqual("成员关联", actual["name"])
        self.assertEqual(button["i18nMessages"], actual["i18nMessages"])
        self.assertEqual(button["options"], actual["options"])
        self.assertEqual([{"permission": "organization", "actions": ["query", "save"]},
                          {"permission": "user", "actions": ["query"]}], actual["permissions"])

    def test_cli_generation_is_repeatable_and_matches_fixture_conversion(self):
        raw = FIXTURE.read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "menus.json"
            subprocess.run([sys.executable, str(SCRIPT), str(FIXTURE), str(output)], check=True, capture_output=True)
            first = output.read_bytes()
            subprocess.run([sys.executable, str(SCRIPT), str(FIXTURE), str(output)], check=True, capture_output=True)
            self.assertEqual(first, output.read_bytes())
            self.assertEqual(json.loads(first), GENERATOR.convert(self.source)[0])
        self.assertEqual(digest, hashlib.sha256(FIXTURE.read_bytes()).hexdigest())

    def test_packaged_menu_contract_has_chinese_names_and_english_locales(self):
        # The maintained production seed is independent of this synthetic legacy export.
        roots = json.loads(PACKAGED.read_text())
        rows = flatten(roots)
        self.assertEqual(2, len(roots))
        self.assertEqual(14, len(rows))
        self.assertEqual(38, sum(len(row["buttons"]) for row in rows.values()))
        for row in rows.values():
            self.assertEqual("proto", row["owner"])
            self.assertEqual(row["name"], row["i18nMessages"]["name"]["zh_CN"])
            self.assertTrue(any("\u4e00" <= c <= "\u9fff" for c in row["name"]))
            self.assertTrue(row["i18nMessages"]["name"]["en_US"].strip())
            self.assertTrue(row["i18nMessages"]["name"]["en"].strip())
            for button in row["buttons"]:
                self.assertEqual(button["name"], button["i18nMessages"]["zh_CN"])
                self.assertTrue(button["i18nMessages"]["en_US"].strip())
                self.assertTrue(button["i18nMessages"]["en"].strip())


if __name__ == "__main__":
    unittest.main()
