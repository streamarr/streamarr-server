#!/bin/bash

ffmpeg_bin="/workspace/.ffmpeg/bin"
if [ -d "${ffmpeg_bin}" ]; then
  export PATH="${ffmpeg_bin}:${PATH}"
fi
