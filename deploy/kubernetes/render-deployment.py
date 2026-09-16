#!/usr/bin/env python3
"""Render the existing deployment with an explicitly selected worker image."""

import json
import os
from pathlib import Path
import sys

worker_image = os.environ.get("STREAMARR_WORKER_IMAGE", "").strip()
if not worker_image:
    sys.exit("Set STREAMARR_WORKER_IMAGE to the verified standalone worker image")

template = Path(__file__).with_name("distributed-transcoding.yaml").read_text()
sys.stdout.write(template.replace("${STREAMARR_WORKER_IMAGE}", json.dumps(worker_image)))
