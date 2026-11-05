import unittest
from check_vulnerabilities import parse_dependencies, load_ignores, findings

SAMPLE = """
releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0
|    +--- org.jetbrains:annotations:13.0 -> 23.0.0
|    \\--- org.jetbrains.kotlin:kotlin-stdlib-common:2.2.0 (c)
+--- project :app:core-model
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0 (*)
+--- com.google.crypto.tink:tink-android:1.24.0
|    +--- com.google.code.gson:gson:2.13.2
|    \\--- androidx.annotation:annotation-jvm:1.8.2 -> 1.9.1
+--- androidx.compose.ui:ui -> 1.9.2
\\--- com.example:lib:{strictly 1.0} -> 1.0
"""

class Parse(unittest.TestCase):
    def test_libraries_with_declared_and_resolved_versions(self):
        deps = parse_dependencies(SAMPLE)
        self.assertIn(("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.0"), deps)
        self.assertIn(("com.google.crypto.tink", "tink-android", "1.24.0"), deps)
        self.assertIn(("com.google.code.gson", "gson", "2.13.2"), deps)

    def test_a_replaced_version_counts_as_the_one_that_ships(self):
        deps = parse_dependencies(SAMPLE)
        self.assertIn(("org.jetbrains", "annotations", "23.0.0"), deps)
        self.assertNotIn(("org.jetbrains", "annotations", "13.0"), deps)
        self.assertIn(("androidx.annotation", "annotation-jvm", "1.9.1"), deps)
        self.assertIn(("androidx.compose.ui", "ui", "1.9.2"), deps, "a library with no declared version (from a BOM)")

    def test_projects_and_constraints_are_left_out(self):
        deps = parse_dependencies(SAMPLE)
        self.assertFalse(any(g.startswith("project") for g, _, _ in deps))
        self.assertNotIn(("org.jetbrains.kotlin", "kotlin-stdlib-common", "2.2.0"), deps)

    def test_nothing_is_found_in_text_without_libraries(self):
        self.assertEqual(set(), parse_dependencies("no dependencies\n\n------\n"))

class Ignores(unittest.TestCase):
    def test_ids_are_read_with_their_reasons_ignored(self):
        text = "# header\n\nGHSA-aaaa-bbbb-cccc  # not reachable in our use, until 2027-01-01\nCVE-2026-1  \n"
        self.assertEqual({"GHSA-aaaa-bbbb-cccc", "CVE-2026-1"}, load_ignores(text))

    def test_an_empty_file_ignores_nothing(self):
        self.assertEqual(set(), load_ignores(""))

class Findings(unittest.TestCase):
    deps = [("a", "one", "1"), ("b", "two", "2"), ("c", "three", "3")]
    results = [{}, {"vulns": [{"id": "GHSA-1"}, {"id": "CVE-2"}]}, {"vulns": [{"id": "GHSA-9"}]}]

    def test_only_libraries_with_vulnerabilities_are_reported(self):
        out = findings(self.deps, self.results, set())
        self.assertEqual([(("b", "two", "2"), ["CVE-2", "GHSA-1"]), (("c", "three", "3"), ["GHSA-9"])], out)

    def test_an_ignored_id_is_not_reported_and_a_library_with_only_ignored_ids_disappears(self):
        out = findings(self.deps, self.results, {"GHSA-9", "CVE-2"})
        self.assertEqual([(("b", "two", "2"), ["GHSA-1"])], out)
        self.assertEqual([], findings(self.deps, self.results, {"GHSA-1", "CVE-2", "GHSA-9"}))

    def test_a_clean_result_gives_nothing(self):
        self.assertEqual([], findings(self.deps, [{}, {}, {}], set()))

if __name__ == "__main__":
    unittest.main()
