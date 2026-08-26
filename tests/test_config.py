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
