"""
Tier 1: Feature F01 & F02 Build Environment & Gradle Infrastructure Tests.
Conforms strictly to TEST_INFRA.md specifications.
"""

import os
import sys
import subprocess

try:
    import tomllib
except ImportError:
    tomllib = None

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def _get_java_home() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        # Check standard installation path
        default_path = r"C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot"
        if os.path.exists(default_path):
            return default_path
    return java_home or ""


def _get_android_sdk() -> str:
    sdk_dir = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_dir:
        local_props = os.path.join(REPO_ROOT, "local.properties")
        if os.path.exists(local_props):
            with open(local_props, "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("sdk.dir="):
                        sdk_dir = line.split("=", 1)[1].strip().replace(r"\:", ":").replace(r"\\", "\\")
    if not sdk_dir:
        default_sdk = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")
        if os.path.exists(default_sdk):
            sdk_dir = default_sdk
    return sdk_dir or ""


# ==============================================================================
# F01: JDK 21 & Build Environment Setup
# ==============================================================================

def test_t1_f01_01_jdk21_installation():
    """T1_F01_01: Verify JDK 21 installation (javac -version contains 21)."""
    java_home = _get_java_home()
    assert java_home, "JAVA_HOME or JDK 21 directory not found"
    javac_exe = os.path.join(java_home, "bin", "javac.exe")
    assert os.path.isfile(javac_exe), f"javac.exe not found at {javac_exe}"

    result = subprocess.run([javac_exe, "-version"], capture_output=True, text=True, check=True)
    version_out = result.stdout + result.stderr
    assert "21." in version_out, f"Expected JDK 21 but got: {version_out}"


def test_t1_f01_02_java_home_validity():
    """T1_F01_02: Verify JAVA_HOME points to valid JDK directory with bin/javac.exe."""
    java_home = _get_java_home()
    assert os.path.isdir(java_home), f"JAVA_HOME '{java_home}' is not a directory"
    assert os.path.exists(os.path.join(java_home, "bin", "javac.exe")), "javac.exe missing in bin/"
    assert os.path.exists(os.path.join(java_home, "bin", "java.exe")), "java.exe missing in bin/"


def test_t1_f01_03_android_sdk_root():
    """T1_F01_03: Verify Android SDK root directory exists and contains platform-tools."""
    sdk_dir = _get_android_sdk()
    assert sdk_dir, "Android SDK root directory could not be resolved"
    assert os.path.isdir(sdk_dir), f"Android SDK dir '{sdk_dir}' does not exist"
    platform_tools = os.path.join(sdk_dir, "platform-tools")
    assert os.path.isdir(platform_tools), f"platform-tools directory not found in {sdk_dir}"


def test_t1_f01_04_android_build_tools():
    """T1_F01_04: Verify Android build-tools version >= 34.0.0 or 35.0.0 present."""
    sdk_dir = _get_android_sdk()
    build_tools_dir = os.path.join(sdk_dir, "build-tools")
    assert os.path.isdir(build_tools_dir), f"build-tools directory missing in {sdk_dir}"
    versions = os.listdir(build_tools_dir)
    has_valid_version = any("34." in v or "35." in v for v in versions)
    assert has_valid_version, f"Expected build-tools 34.0.0 or 35.0.0, found: {versions}"


def test_t1_f01_05_compile_sdk_platform():
    """T1_F01_05: Verify Android compile SDK platform android-35 present."""
    sdk_dir = _get_android_sdk()
    platforms_dir = os.path.join(sdk_dir, "platforms")
    assert os.path.isdir(platforms_dir), f"platforms directory missing in {sdk_dir}"
    platforms = os.listdir(platforms_dir)
    assert "android-35" in platforms, f"android-35 compile SDK missing, found: {platforms}"


# ==============================================================================
# F02: Gradle Blocker Remediation
# ==============================================================================

def test_t1_f02_01_gradle_properties_no_hardcoded_paths():
    """T1_F02_01: Verify gradle.properties does NOT contain hardcoded developer paths."""
    props_path = os.path.join(REPO_ROOT, "gradle.properties")
    assert os.path.isfile(props_path), "gradle.properties missing"
    with open(props_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "siddh" not in content.lower(), "Found hardcoded developer path 'siddh' in gradle.properties"
    assert "org.gradle.jvmargs" in content, "Missing org.gradle.jvmargs in gradle.properties"


def test_t1_f02_02_gradle_wrapper_script():
    """T1_F02_02: Verify Gradle wrapper script gradlew.bat exists and is valid."""
    gradlew_bat = os.path.join(REPO_ROOT, "gradlew.bat")
    assert os.path.isfile(gradlew_bat), "gradlew.bat script not found"
    wrapper_jar = os.path.join(REPO_ROOT, "gradle", "wrapper", "gradle-wrapper.jar")
    assert os.path.isfile(wrapper_jar), "gradle-wrapper.jar missing"


def test_t1_f02_03_version_catalog_validity():
    """T1_F02_03: Verify gradle/libs.versions.toml declares AGP, Kotlin, Compose, Room."""
    toml_path = os.path.join(REPO_ROOT, "gradle", "libs.versions.toml")
    assert os.path.isfile(toml_path), "libs.versions.toml missing"
    with open(toml_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "agp" in content, "Missing AGP version in libs.versions.toml"
    assert "kotlin" in content, "Missing Kotlin version in libs.versions.toml"
    assert "compose" in content.lower(), "Missing Compose dependencies in libs.versions.toml"
    assert "room" in content.lower(), "Missing Room dependencies in libs.versions.toml"


def test_t1_f02_04_settings_gradle_configuration():
    """T1_F02_04: Verify settings.gradle.kts declares rootProject.name = 'iTantra' and includes :app."""
    settings_path = os.path.join(REPO_ROOT, "settings.gradle.kts")
    assert os.path.isfile(settings_path), "settings.gradle.kts missing"
    with open(settings_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert 'rootProject.name = "iTantra"' in content, "rootProject.name must be 'iTantra'"
    assert 'include(":app")' in content, "settings.gradle.kts must include :app"


def test_t1_f02_05_root_build_script_syntax():
    """T1_F02_05: Verify root build.gradle.kts has plugins DSL configured properly."""
    build_path = os.path.join(REPO_ROOT, "build.gradle.kts")
    assert os.path.isfile(build_path), "build.gradle.kts missing"
    with open(build_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "plugins" in content, "build.gradle.kts must configure plugins DSL"
