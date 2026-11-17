"""
Unified Command-Line Test Runner for iTantra (SIH26173) E2E Test Suite.
Supports:
  --tier [1|2|3|4|all]
  --feature [F1..F20]
  --mode [hardware|simulated]
  --report-json <path>
  --report-junit <path>

Conforms strictly to TEST_INFRA.md.
"""

import os
import sys
import time
import json
import argparse
import inspect
import importlib
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

# Ensure repository root is on sys.path
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

# ANSI Colors
COLOR_RESET = "\033[0m"
COLOR_GREEN = "\033[32m"
COLOR_RED = "\033[31m"
COLOR_YELLOW = "\033[33m"
COLOR_CYAN = "\033[36m"
COLOR_BOLD = "\033[1m"


class TestResult:
    def __init__(self, test_id: str, name: str, tier: int, feature: str, passed: bool, duration: float, error: str = ""):
        self.test_id = test_id
        self.name = name
        self.tier = tier
        self.feature = feature
        self.passed = passed
        self.duration = duration
        self.error = error


class E2ETestRunner:
    def __init__(self, tier: str = "all", feature: str = None, mode: str = "simulated",
                 report_json: str = None, report_junit: str = None):
        self.selected_tier = tier.lower() if tier else "all"
        self.selected_feature = feature.upper() if feature else None
        self.mode = mode.lower() if mode else "simulated"
        self.report_json = report_json
        self.report_junit = report_junit
        self.results = []

    def discover_test_modules(self) -> List[Tuple[int, str, Any]]:
        """Discovers all test modules according to tier layout."""
        discovered = []
        base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

        tier_folders = {
            1: "tier1_feature",
            2: "tier2_boundaries",
            3: "tier3_pairwise",
            4: "tier4_scenarios"
        }

        for tier_num, folder in tier_folders.items():
            if self.selected_tier != "all" and str(tier_num) != self.selected_tier:
                continue

            folder_path = os.path.join(base_dir, folder)
            if not os.path.exists(folder_path):
                continue

            for file in sorted(os.listdir(folder_path)):
                if file.startswith("test_") and file.endswith(".py"):
                    mod_name = f"tests.e2e.{folder}.{file[:-3]}"
                    try:
                        mod = importlib.import_module(mod_name)
                        discovered.append((tier_num, mod_name, mod))
                    except Exception as e:
                        print(f"{COLOR_RED}[LOAD ERROR] Failed to load {mod_name}: {e}{COLOR_RESET}")

        return discovered

    def run(self) -> int:
        """Executes the discovered test suite and generates reports."""
        start_time = time.time()
        print(f"\n{COLOR_CYAN}{COLOR_BOLD}======================================================================{COLOR_RESET}")
        print(f"{COLOR_CYAN}{COLOR_BOLD}   iTantra (SIH26173) 4-Tier E2E Master Test Suite Runner{COLOR_RESET}")
        print(f"{COLOR_CYAN}{COLOR_BOLD}======================================================================{COLOR_RESET}")
        print(f" Mode: {COLOR_YELLOW}{self.mode.upper()}{COLOR_RESET} | "
              f"Tier: {COLOR_YELLOW}{self.selected_tier.upper()}{COLOR_RESET} | "
              f"Feature Filter: {COLOR_YELLOW}{self.selected_feature or 'ALL'}{COLOR_RESET}\n")

        # Set environment mode for tests to read
        os.environ["ITANTRA_TEST_MODE"] = self.mode

        modules = self.discover_test_modules()
        if not modules:
            print(f"{COLOR_RED}No test modules found for criteria.{COLOR_RESET}")
            return 2

        total_discovered = 0
        current_tier = None

        for tier_num, mod_name, mod in modules:
            if tier_num != current_tier:
                current_tier = tier_num
                print(f"\n{COLOR_BOLD}--- Tier {tier_num} Execution ---{COLOR_RESET}")

            # Find all test_* functions
            for attr_name in dir(mod):
                if attr_name.startswith("test_"):
                    func = getattr(mod, attr_name)
                    if callable(func):
                        # Extract metadata
                        doc = inspect.getdoc(func) or ""
                        # Derive feature (e.g., F1, F07, F14)
                        feat = "F0"
                        for part in attr_name.upper().split("_"):
                            if part.startswith("F") and part[1:].isdigit():
                                feat = f"F{int(part[1:])}"
                                break

                        # Feature filtering
                        if self.selected_feature:
                            # Normalize feature match: e.g. "F1" matches "F1" or "F01"
                            sel_norm = f"F{int(self.selected_feature[1:])}" if self.selected_feature[1:].isdigit() else self.selected_feature
                            feat_norm = f"F{int(feat[1:])}" if feat[1:].isdigit() else feat
                            if sel_norm != feat_norm:
                                continue

                        total_discovered += 1
                        test_start = time.time()
                        passed = True
                        error_msg = ""
                        try:
                            func()
                        except Exception as e:
                            passed = False
                            error_msg = str(e)
                            import traceback
                            error_msg += "\n" + traceback.format_exc()

                        elapsed = time.time() - test_start
                        res = TestResult(
                            test_id=attr_name,
                            name=doc.split("\n")[0] if doc else attr_name,
                            tier=tier_num,
                            feature=feat,
                            passed=passed,
                            duration=elapsed,
                            error=error_msg
                        )
                        self.results.append(res)

                        status_str = f"{COLOR_GREEN}PASS{COLOR_RESET}" if passed else f"{COLOR_RED}FAIL{COLOR_RESET}"
                        print(f"  [{status_str}] {attr_name} ({elapsed:.3f}s)")
                        if not passed:
                            print(f"         {COLOR_RED}{error_msg.splitlines()[-1]}{COLOR_RESET}")

        total_duration = time.time() - start_time
        passed_count = sum(1 for r in self.results if r.passed)
        failed_count = sum(1 for r in self.results if not r.passed)

        # Print Summary Table
        print(f"\n{COLOR_CYAN}{COLOR_BOLD}======================================================================{COLOR_RESET}")
        print(f"{COLOR_CYAN}{COLOR_BOLD}                           TEST SUMMARY{COLOR_RESET}")
        print(f"{COLOR_CYAN}{COLOR_BOLD}======================================================================{COLOR_RESET}")
        print(f" Total Tests Executed: {COLOR_BOLD}{len(self.results)}{COLOR_RESET}")
        print(f" Passed:               {COLOR_GREEN}{COLOR_BOLD}{passed_count}{COLOR_RESET}")
        print(f" Failed:               {COLOR_RED if failed_count > 0 else COLOR_GREEN}{COLOR_BOLD}{failed_count}{COLOR_RESET}")
        print(f" Total Elapsed Time:   {total_duration:.2f}s")
        print(f" Mode:                 {self.mode.upper()}")
        print(f"{COLOR_CYAN}{COLOR_BOLD}======================================================================{COLOR_RESET}")

        # Export Reports
        if self.report_json:
            self._export_json(self.report_json, total_duration)
        if self.report_junit:
            self._export_junit(self.report_junit, total_duration)

        if failed_count == 0 and len(self.results) > 0:
            print(f"\n{COLOR_GREEN}{COLOR_BOLD}>>> ALL TESTS PASSED SUCCESSFULLY! <<<{COLOR_RESET}\n")
            return 0
        elif len(self.results) == 0:
            print(f"\n{COLOR_RED}{COLOR_BOLD}>>> NO TESTS MATCHED FILTER CRITERIA! <<<{COLOR_RESET}\n")
            return 1
        else:
            print(f"\n{COLOR_RED}{COLOR_BOLD}>>> TEST FAILURES DETECTED! <<<{COLOR_RESET}\n")
            return 1

    def _export_json(self, path: str, total_time: float):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        report = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "total": len(self.results),
            "passed": sum(1 for r in self.results if r.passed),
            "failed": sum(1 for r in self.results if not r.passed),
            "elapsed_seconds": total_time,
            "mode": self.mode,
            "results": [
                {
                    "test_id": r.test_id,
                    "name": r.name,
                    "tier": r.tier,
                    "feature": r.feature,
                    "passed": r.passed,
                    "duration": r.duration,
                    "error": r.error if not r.passed else ""
                }
                for r in self.results
            ]
        }
        with open(path, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"{COLOR_CYAN}Exported JSON report to: {path}{COLOR_RESET}")

    def _export_junit(self, path: str, total_time: float):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        testsuites = ET.Element("testsuites", {
            "name": "iTantra_E2E_Tests",
            "time": f"{total_time:.3f}",
            "tests": str(len(self.results)),
            "failures": str(sum(1 for r in self.results if not r.passed))
        })
        suite = ET.SubElement(testsuites, "testsuite", {
            "name": f"Tier_{self.selected_tier}_Suite",
            "tests": str(len(self.results)),
            "failures": str(sum(1 for r in self.results if not r.passed)),
            "time": f"{total_time:.3f}"
        })
        for r in self.results:
            case = ET.SubElement(suite, "testcase", {
                "classname": f"Tier{r.tier}.{r.feature}",
                "name": r.test_id,
                "time": f"{r.duration:.3f}"
            })
            if not r.passed:
                failure = ET.SubElement(case, "failure", {"message": "Test assertion failed"})
                failure.text = r.error
        tree = ET.ElementTree(testsuites)
        tree.write(path, encoding="utf-8", xml_declaration=True)
        print(f"{COLOR_CYAN}Exported JUnit XML report to: {path}{COLOR_RESET}")


def main():
    parser = argparse.ArgumentParser(description="iTantra E2E Test Suite Runner")
    parser.add_argument("--tier", default="all", choices=["1", "2", "3", "4", "all"],
                        help="Select test tier (1, 2, 3, 4, or all)")
    parser.add_argument("--feature", default=None,
                        help="Filter by feature (e.g. F1..F20)")
    parser.add_argument("--mode", default="simulated", choices=["hardware", "simulated"],
                        help="Test mode: real hardware (COM6/COM7) or simulated transceiver")
    parser.add_argument("--report-json", default=None,
                        help="File path to save JSON test results")
    parser.add_argument("--report-junit", default=None,
                        help="File path to save JUnit XML test results")

    args = parser.parse_args()

    runner = E2ETestRunner(
        tier=args.tier,
        feature=args.feature,
        mode=args.mode,
        report_json=args.report_json,
        report_junit=args.report_junit
    )
    sys.exit(runner.run())


if __name__ == "__main__":
    main()
