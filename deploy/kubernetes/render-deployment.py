#!/usr/bin/env python3
"""Render the deployment with the checked-in worker pin or an explicit override."""

import json
import os
import re
from pathlib import Path
import sys

worker_image = os.environ.get("STREAMARR_WORKER_IMAGE", "").strip()
if not worker_image:
    pin = Path(__file__).resolve().parents[2] / "worker-image.env"
    assignments = [line.strip() for line in pin.read_text().splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
    if len(assignments) != 1 or not assignments[0].startswith("STREAMARR_WORKER_IMAGE="):
        sys.exit("worker-image.env must contain one STREAMARR_WORKER_IMAGE=<image> assignment")
    worker_image = assignments[0].partition("=")[2]
    if not re.fullmatch(r"[A-Za-z0-9._:/-]+@sha256:[0-9a-f]{64}", worker_image):
        sys.exit("worker-image.env must pin an unquoted image with an immutable sha256 digest")

template = Path(__file__).with_name("distributed-transcoding.yaml").read_text()
sys.stdout.write(template.replace("${STREAMARR_WORKER_IMAGE}", json.dumps(worker_image)))
