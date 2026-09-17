#!/usr/bin/env python3
"""Render the deployment with the checked-in worker pin or an explicit override."""

import json
import os
from pathlib import Path
import sys

worker_image = os.environ.get("STREAMARR_WORKER_IMAGE", "").strip()
if not worker_image:
    pin = Path(__file__).resolve().parents[2] / "worker-image.env"
    worker_image = pin.read_text().strip().removeprefix("STREAMARR_WORKER_IMAGE=")

template = Path(__file__).with_name("distributed-transcoding.yaml").read_text()
sys.stdout.write(template.replace("${STREAMARR_WORKER_IMAGE}", json.dumps(worker_image)))
