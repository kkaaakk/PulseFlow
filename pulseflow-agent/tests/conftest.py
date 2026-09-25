"""No real model provider may be contacted by ordinary tests or CI."""

from pydantic_ai import models

models.ALLOW_MODEL_REQUESTS = False
