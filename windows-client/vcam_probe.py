#!/usr/bin/env python3
"""Diagnostic: verify the OpenCam Virtual Camera opens while pyvirtualcam feeds it."""
import subprocess
import sys
import threading
import time

import numpy as np
import pyvirtualcam

DEVICE = sys.argv[1] if len(sys.argv) > 1 else "OpenCam Virtual Camera"

stop = threading.Event()


def producer():
    try:
        with pyvirtualcam.Camera(width=640, height=360, fps=30,
                                 fmt=pyvirtualcam.PixelFormat.RGB) as vc:
            t0 = time.time()
            while not stop.is_set() and time.time() - t0 < 15:
                f = np.zeros((360, 640, 3), dtype=np.uint8)
                f[:, :, 0] = 255  # red background
                x = int((time.time() * 40) % 540)
                f[:, x:x + 100, 1] = 255  # green moving bar
                vc.send(f)
                vc.sleep_until_next_frame()
    except Exception as e:
        print("producer err:", e)


th = threading.Thread(target=producer, daemon=True)
th.start()
time.sleep(2)
print("capturing from %r while producer runs..." % DEVICE)
r = subprocess.run(
    ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "dshow",
     "-i", "video=" + DEVICE, "-frames:v", "1", "-update", "1", "-y", "vcam_proof.png"],
    capture_output=True, text=True, timeout=45)
stop.set()
time.sleep(0.5)
print("ffmpeg rc:", r.returncode)
if r.stderr:
    print("stderr tail:", r.stderr[-250:])
if r.returncode == 0:
    from PIL import Image
    img = Image.open("vcam_proof.png").convert("RGB")
    small = img.resize((64, 36))
    colors = sorted(small.getcolors(64 * 36), reverse=True)[:3]
    print("captured OK, size", img.size, "top colors:", colors)
