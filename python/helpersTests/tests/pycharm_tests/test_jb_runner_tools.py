import os
import re
import subprocess
import sys
import textwrap

from testing import HelpersTestCase
from testing import _helpers_pycharm_root
from testing import python3_only

@python3_only
class JBRunnerToolsSubtestTest(HelpersTestCase):
    def test_serial_mode_subtests(self):
        self._write("test_sub.py", """\
            import unittest

            class SubTests(unittest.TestCase):
                def test_described(self):
                    \"\"\"Has a description.\"\"\"
                    with self.subTest(x=1):
                        pass

                def test_dotted(self):
                    with self.subTest("a.b"):
                        self.fail("dotted")

                def test_subtests(self):
                    for i in range(3):
                        with self.subTest(i=i):
                            if i == 2:
                                self.skipTest("skip two")
                            self.assertNotEqual(i, 1)
        """)

        result = self._run([os.path.join(_helpers_pycharm_root, "_jb_unittest_runner.py"), "--target", "test_sub.SubTests"])

        self.assertEqual(result.returncode, 1, msg=result.stdout + result.stderr)
        # Python 3.9 and 3.10 report a skipped subtest before the other subtests, so the order is not checked
        self.assertEqual(
            self._test_tree(result.stdout),
            {
                "test_described (Has a description_)": ("SubTests", "passed"),
                "(x=1)": ("test_described (Has a description_)", "passed"),
                "test_dotted": ("SubTests", "failed"),
                "|[a_b|]": ("test_dotted", "failed"),
                "test_subtests": ("SubTests", "failed"),
                "(i=0)": ("test_subtests", "passed"),
                "(i=1)": ("test_subtests", "failed"),
                "(i=2)": ("test_subtests", "ignored"),
            },
        )

    def test_parallel_mode_subtest_is_under_its_test(self):
        # Another test starts before the subtest of the first test, as with parallel workers
        self._write("report.py", """\
            import sys

            import _jb_runner_tools

            _jb_runner_tools.set_parallel_mode()
            _jb_runner_tools.start_protocol()
            messages = _jb_runner_tools.NewTeamcityServiceMessages(sys.stdout)
            messages.testStarted("mod.FirstTest.test_one", flowId="mod.FirstTest.test_one")
            messages.testStarted("mod.SecondTest.test_two", flowId="mod.SecondTest.test_two")
            messages.subTestBlockOpened("(i=1)", subTestResult="Failure", flowId="mod.FirstTest.test_one")
            messages.blockClosed("(i=1)", flowId="mod.FirstTest.test_one")
            messages.testFinished("mod.SecondTest.test_two", flowId="mod.SecondTest.test_two")
            messages.testFinished("mod.FirstTest.test_one", flowId="mod.FirstTest.test_one")
        """)

        result = self._run([self.resolve_in_temp_dir("report.py")])

        self.assertEqual(result.returncode, 0, msg=result.stdout + result.stderr)
        self.assertEqual(
            self._test_tree(result.stdout),
            {
                "test_one": ("FirstTest", "passed"),
                "test_two": ("SecondTest", "passed"),
                "(i=1)": ("test_one", "failed"),
            },
        )

    def _write(self, name, content):
        with open(self.resolve_in_temp_dir(name), "w") as f:
            f.write(textwrap.dedent(content))

    def _run(self, args):
        env = os.environ.copy()
        env.pop("JB_UNITTEST_RUNNER_SCRIPT", None)
        env.pop("JB_USE_PARALLEL_TREE_MANAGER", None)
        env["PWD"] = self.temp_dir
        env["PYTHONPATH"] = _helpers_pycharm_root
        return subprocess.run([sys.executable] + args, cwd=self.temp_dir, env=env, capture_output=True, text=True)

    @staticmethod
    def _test_tree(output):
        """
        :return: {test name: (parent name, result)} for each test node
        """
        names = {"0": None}
        parents = {}
        results = {}
        for line in output.splitlines():
            match = re.match(r"##teamcity\[(\w+) ", line)
            if not match:
                continue
            attributes = dict(re.findall(r" (\w+)='((?:[^'|]|\|.)*)'", line))
            event = match.group(1)
            if event in ("testSuiteStarted", "testStarted"):
                names[attributes["nodeId"]] = attributes["name"]
                parents[attributes["nodeId"]] = attributes["parentNodeId"]
            if event == "testStarted":
                results[attributes["nodeId"]] = "passed"
            elif event == "testFailed":
                results[attributes["nodeId"]] = "failed"
            elif event == "testIgnored":
                results[attributes["nodeId"]] = "ignored"
        return {names[node_id]: (names[parents[node_id]], result) for node_id, result in results.items()}
