from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "http://schemas.android.com/apk/res/android"


def test_android_production_manifest_disables_cleartext_transport():
    manifest = ElementTree.parse(ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml")
    application = manifest.getroot().find("application")
    assert application is not None
    assert application.attrib[f"{{{ANDROID_NS}}}usesCleartextTraffic"] == "false"


def test_apk_build_configuration_contains_public_url_but_no_api_token():
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'buildConfigField("String", "KUBER_API_BASE_URL"' in gradle
    assert "KUBER_API_TOKEN" not in gradle
