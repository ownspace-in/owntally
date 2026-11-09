"""Tests for assetlinks.py (M9, T9.4). Run: python3 -m unittest -v test_assetlinks (from the site/ folder). Needs Python 3 only."""
import json, os, tempfile, unittest

import assetlinks

FP1 = "14:6D:E9:83:C5:73:06:50:D8:EE:B9:95:2F:34:FC:64:16:A0:83:42:E6:1D:BE:A8:8A:04:96:B2:3F:CF:44:E5"
FP2 = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"


class NormaliseTest(unittest.TestCase):
    def test_accepts_the_ways_a_fingerprint_is_written(self):
        plain = FP1.replace(":", "")
        for text in (FP1, FP1.lower(), plain, plain.lower(), " " + FP1 + "\n", "SHA256: " + FP1, "sha-256 " + plain):
            self.assertEqual(FP1, assetlinks.normalise(text), text)

    def test_refuses_what_is_not_a_sha256(self):
        for text in ("", "12:34", FP1 + ":00", FP1[:-3], "ZZ" + FP1[2:], "0" * 64, "00:" * 31 + "00", "F" * 63):
            with self.assertRaises(assetlinks.Problem, msg=text):
                assetlinks.normalise(text)

    def test_refuses_the_placeholder_of_the_template(self):
        with self.assertRaises(assetlinks.Problem):
            assetlinks.normalise(assetlinks.PLACEHOLDER)


class MakeTest(unittest.TestCase):
    def test_makes_the_file_android_reads(self):
        data = json.loads(assetlinks.make("in.ownspace.owntally", [FP1]))
        self.assertEqual(
            [{"relation": ["delegate_permission/common.handle_all_urls"],
              "target": {"namespace": "android_app", "package_name": "in.ownspace.owntally", "sha256_cert_fingerprints": [FP1]}}],
            data)

    def test_several_fingerprints_each_once_in_order(self):
        data = json.loads(assetlinks.make("in.ownspace.owntally", [FP1, FP2.lower(), FP1.replace(":", "")]))
        self.assertEqual([FP1, FP2], data[0]["target"]["sha256_cert_fingerprints"])

    def test_needs_a_fingerprint_and_a_real_package_name(self):
        with self.assertRaises(assetlinks.Problem): assetlinks.make("in.ownspace.owntally", [])
        for name in ("", "owntally", "in.ownspace.", "in ownspace.owntally", "1in.ownspace.owntally", "<application id>"):
            with self.assertRaises(assetlinks.Problem, msg=name): assetlinks.make(name, [FP1])

    def test_the_default_package_is_the_apps(self):
        self.assertEqual("in.ownspace.owntally", assetlinks.DEFAULT_PACKAGE)


class CheckTest(unittest.TestCase):
    def good(self): return assetlinks.make("in.ownspace.owntally", [FP1])

    def test_a_good_file_has_no_problems(self):
        self.assertEqual([], assetlinks.check_text(self.good(), "in.ownspace.owntally", [FP1]))

    def test_names_what_is_wrong(self):
        bad = {
            "not json": "{",
            "not a list": "{}",
            "wrong package": assetlinks.make("in.example.other", [FP1]),
        }
        for name, text in bad.items():
            self.assertTrue(assetlinks.check_text(text, "in.ownspace.owntally", [FP1]), name)
        self.assertTrue(assetlinks.check_text(self.good(), "in.ownspace.owntally", [FP2]), "the fingerprint asked for is not in the file")
        placeholder = json.dumps([{"relation": ["delegate_permission/common.handle_all_urls"], "target": {"namespace": "android_app", "package_name": "in.ownspace.owntally", "sha256_cert_fingerprints": [assetlinks.PLACEHOLDER]}}])
        self.assertTrue(assetlinks.check_text(placeholder, "in.ownspace.owntally", []), "the template placeholder is not a certificate")

    def test_the_relation_must_be_the_one_that_opens_links(self):
        data = json.loads(self.good()); data[0]["relation"] = ["delegate_permission/common.get_login_creds"]
        self.assertTrue(assetlinks.check_text(json.dumps(data), "in.ownspace.owntally", [FP1]))

    def test_the_http_answer_must_be_200_json_and_not_a_redirect(self):
        self.assertEqual([], assetlinks.check_http(200, "application/json", False))
        self.assertEqual([], assetlinks.check_http(200, "application/json; charset=utf-8", False))
        self.assertTrue(assetlinks.check_http(404, "application/json", False))
        self.assertTrue(assetlinks.check_http(200, "text/html", False))
        self.assertTrue(assetlinks.check_http(200, "application/json", True), "a redirect: Android does not follow it")

    def test_the_template_in_the_repo_is_a_template_not_a_real_file(self):
        here = os.path.dirname(os.path.abspath(__file__))
        text = open(os.path.join(here, "assetlinks.template.json")).read()
        problems = assetlinks.check_text(text, assetlinks.DEFAULT_PACKAGE, [])
        self.assertTrue(any("placeholder" in p for p in problems), problems)


class CommandLineTest(unittest.TestCase):
    def test_make_writes_a_file_and_check_accepts_it(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, ".well-known", "assetlinks.json")
            self.assertEqual(0, assetlinks.main(["make", "--fingerprint", FP1, "--out", out]))
            self.assertEqual(0, assetlinks.main(["check", "--file", out, "--fingerprint", FP1]))
            self.assertEqual(1, assetlinks.main(["check", "--file", out, "--fingerprint", FP2]))

    def test_make_refuses_a_bad_fingerprint_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "assetlinks.json")
            self.assertEqual(1, assetlinks.main(["make", "--fingerprint", "12:34", "--out", out]))
            self.assertFalse(os.path.exists(out))


if __name__ == "__main__":
    unittest.main()
