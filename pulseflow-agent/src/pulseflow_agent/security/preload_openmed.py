"""Download the configured Chinese PII model into a persistent cache before runtime."""

import os


def main() -> None:
    if os.getenv("PULSEFLOW_AGENT_MODEL", "test") == "test":
        return
    from openmed import ModelLoader, OpenMedConfig

    model = os.getenv(
        "PULSEFLOW_AGENT_PII_MODEL", "OpenMed/OpenMed-PII-Chinese-BigMed-Large-560M-v1"
    )
    cache = os.getenv("PULSEFLOW_AGENT_PII_CACHE_DIR", "~/.cache/openmed")
    offline = os.getenv("OPENMED_OFFLINE") == "1" or os.getenv("HF_HUB_OFFLINE") == "1"
    loader = ModelLoader(OpenMedConfig(cache_dir=os.path.expanduser(cache), local_only=offline))
    loader.load_model(model)


if __name__ == "__main__":
    main()
