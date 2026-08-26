import os
import unittest

from kuber.config import KuberSettings


class ConfigTests(unittest.TestCase):
    def test_production_requires_api_token(self) -> None:
        previous_environment = os.environ.get("KUBER_ENVIRONMENT")
        previous_token = os.environ.get("KUBER_API_TOKEN")
        try:
            os.environ["KUBER_ENVIRONMENT"] = "production"
            os.environ.pop("KUBER_API_TOKEN", None)
            with self.assertRaises(RuntimeError):
                KuberSettings.from_environment()
        finally:
            if previous_environment is None: os.environ.pop("KUBER_ENVIRONMENT", None)
            else: os.environ["KUBER_ENVIRONMENT"] = previous_environment
            if previous_token is None: os.environ.pop("KUBER_API_TOKEN", None)
            else: os.environ["KUBER_API_TOKEN"] = previous_token

    def test_live_orders_require_production_and_https(self) -> None:
        keys = ("KUBER_ENVIRONMENT", "KUBER_API_TOKEN", "KUBER_ENABLE_LIVE_ORDERS", "KUBER_PUBLIC_BASE_URL", "KUBER_ANGEL_ONE_STATIC_IPV4", "KUBER_ENABLE_ANGEL_ONE_LIVE")
        previous = {key: os.environ.get(key) for key in keys}
        try:
            os.environ["KUBER_ENVIRONMENT"] = "production"
            os.environ["KUBER_API_TOKEN"] = "test-token"
            os.environ["KUBER_ENABLE_LIVE_ORDERS"] = "true"
            os.environ.pop("KUBER_PUBLIC_BASE_URL", None)
            os.environ.pop("KUBER_ANGEL_ONE_STATIC_IPV4", None)
            with self.assertRaises(RuntimeError):
                KuberSettings.from_environment()
        finally:
            for key, value in previous.items():
                if value is None: os.environ.pop(key, None)
                else: os.environ[key] = value
    def test_angel_one_alone_requires_a_static_ipv4(self) -> None:
        keys = ("KUBER_ENVIRONMENT", "KUBER_API_TOKEN", "KUBER_ENABLE_LIVE_ORDERS", "KUBER_PUBLIC_BASE_URL", "KUBER_ANGEL_ONE_STATIC_IPV4", "KUBER_ENABLE_ANGEL_ONE_LIVE")
        previous = {key: os.environ.get(key) for key in keys}
        try:
            os.environ.update({
                "KUBER_ENVIRONMENT": "production",
                "KUBER_API_TOKEN": "t" * 32,
                "KUBER_ENABLE_LIVE_ORDERS": "true",
                "KUBER_PUBLIC_BASE_URL": "https://api.example.com",
                "KUBER_ENABLE_ANGEL_ONE_LIVE": "true",
            })
            os.environ.pop("KUBER_ANGEL_ONE_STATIC_IPV4", None)
            with self.assertRaisesRegex(RuntimeError, "static IPv4"):
                KuberSettings.from_environment()
        finally:
            for key, value in previous.items():
                if value is None: os.environ.pop(key, None)
                else: os.environ[key] = value

    def test_production_requires_origin_only_https_url_and_long_token(self) -> None:
        keys = ("KUBER_ENVIRONMENT", "KUBER_API_TOKEN", "KUBER_PUBLIC_BASE_URL", "KUBER_ALLOWED_HOSTS")
        previous = {key: os.environ.get(key) for key in keys}
        try:
            os.environ.update({
                "KUBER_ENVIRONMENT": "production",
                "KUBER_API_TOKEN": "t" * 32,
                "KUBER_PUBLIC_BASE_URL": "http://api.example.com/path?token=bad",
            })
            with self.assertRaisesRegex(RuntimeError, "origin-only HTTPS"):
                KuberSettings.from_environment()

            os.environ["KUBER_PUBLIC_BASE_URL"] = "https://api.example.com"
            os.environ["KUBER_ALLOWED_HOSTS"] = "api.example.com,localhost"
            settings = KuberSettings.from_environment()
            self.assertEqual(settings.public_base_url, "https://api.example.com")
            self.assertEqual(settings.allowed_hosts, ("api.example.com", "localhost"))
        finally:
            for key, value in previous.items():
                if value is None: os.environ.pop(key, None)
                else: os.environ[key] = value
