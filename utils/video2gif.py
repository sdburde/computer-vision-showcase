#!/usr/bin/env python3

import subprocess
from pathlib import Path
import shutil
import sys


VIDEO_DIR = Path("videos")
GIF_DIR = Path("gifs")

START_TIME = 5
DURATION = 22
FPS = 15
WIDTH = 800


def check_ffmpeg():
    if shutil.which("ffmpeg") is None:
        print("❌ ffmpeg not found. Install it first.")
        sys.exit(1)


def create_gif(video_path, gif_path):

    palette = gif_path.with_suffix(".png")

    palette_cmd = [
        "ffmpeg",
        "-y",
        "-ss", str(START_TIME),
        "-t", str(DURATION),
        "-i", str(video_path),
        "-vf", f"fps={FPS},scale={WIDTH}:-1:flags=lanczos,palettegen",
        str(palette),
    ]

    gif_cmd = [
        "ffmpeg",
        "-y",
        "-ss", str(START_TIME),
        "-t", str(DURATION),
        "-i", str(video_path),
        "-i", str(palette),
        "-lavfi", f"fps={FPS},scale={WIDTH}:-1:flags=lanczos[x];[x][1:v]paletteuse",
        str(gif_path),
    ]

    subprocess.run(palette_cmd, check=True)
    subprocess.run(gif_cmd, check=True)

    palette.unlink(missing_ok=True)


def main():

    check_ffmpeg()

    GIF_DIR.mkdir(exist_ok=True)

    videos = list(VIDEO_DIR.glob("*"))

    for video in videos:

        if video.suffix.lower() not in [".mp4", ".mov", ".avi", ".mkv"]:
            continue

        gif_path = GIF_DIR / f"{video.stem}.gif"

        print(f"🎬 Converting {video.name} → {gif_path.name}")

        create_gif(video, gif_path)

    print("\n✅ All GIFs generated!")


if __name__ == "__main__":
    main()