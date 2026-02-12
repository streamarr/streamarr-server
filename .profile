#!/bin/bash
# Extend LD_LIBRARY_PATH with apt-installed library subdirectories that the
# paketo-buildpacks/apt buildpack does not add automatically.
# PulseAudio installs libpulsecommon into a nested pulseaudio/ directory.

apt_lib="/layers/paketo-buildpacks_apt/apt/usr/lib"
for arch_dir in x86_64-linux-gnu aarch64-linux-gnu; do
  subdir="${apt_lib}/${arch_dir}/pulseaudio"
  if [ -d "${subdir}" ]; then
    export LD_LIBRARY_PATH="${LD_LIBRARY_PATH:+${LD_LIBRARY_PATH}:}${subdir}"
  fi
done
