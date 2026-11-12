"""Makes the home screen's time-of-day wallpapers: app/src/main/res/drawable-nodpi/wallpaper_{dawn,day,dusk,night}.jpg

    python tools/wallpapers.py            # AI art if openai.key is in local.properties, else gradient placeholders
    python tools/wallpapers.py --placeholder
    python tools/wallpapers.py --only dusk

AI mode calls the OpenAI Images API once per part of the day (same scene, different light) and saves 1920x1080
JPEGs. Settings in local.properties (gitignored; the key is never printed or committed):
    openai.key=sk-...
    openai.image_model=gpt-image-1      (optional)
Needs Pillow and numpy.
"""
import argparse
import base64
import io
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent  # launcher/
OUT = ROOT / "app/src/main/res/drawable-nodpi"
WIDTH, HEIGHT = 1920, 1080

# One place, four lights, so the screen changes through the day like a window, not a slideshow.
# Composition rules keep the UI readable: calm, darker left and lower thirds (hero text, app rows), interest upper right.
SCENE = (
    "A serene, minimal landscape photograph for a premium TV home screen: a still alpine lake in a wide valley, "
    "layered mountain ridges fading into soft atmospheric haze, the main peak and light source in the upper right "
    "third. The left half and the bottom third are calm and uncluttered (open water and sky) with gentle, deep tones, "
    "so white text placed there stays readable. Cinematic, photographic, shot on a medium-format camera, natural "
    "colour grading, subtle film grain, ultra clean. No people, no animals, no text, no logos, no watermark, no frame."
)
LIGHT = {
    "dawn": "Blue hour turning into dawn: deep indigo sky warming to soft peach and rose at the horizon, low mist on the water.",
    "day": "Clear late-morning light: crisp deep blue sky, cool teal water, soft white clouds, gentle contrast.",
    "dusk": "Golden hour into dusk: amber and coral light on the peaks, violet sky above, warm reflections on the lake.",
    "night": "Night: near-black navy sky with faint stars and a thin moonlit glow on the ridges, very dark and quiet, "
             "mostly black so it is gentle on an OLED screen.",
}

# Placeholder palettes: (top colour, bottom colour, [(x, y, radius, colour, strength), ...]) in 0..1 coordinates
PLACEHOLDER = {
    "dawn": ((18, 20, 52), (34, 24, 58), [(0.78, 0.62, 0.42, (255, 168, 140), 0.55), (0.92, 0.3, 0.3, (140, 120, 220), 0.35)]),
    "day": ((10, 38, 72), (6, 52, 70), [(0.8, 0.28, 0.45, (90, 180, 230), 0.5), (0.6, 0.85, 0.4, (40, 150, 160), 0.35)]),
    "dusk": ((30, 16, 52), (44, 18, 34), [(0.82, 0.55, 0.42, (255, 140, 70), 0.55), (0.65, 0.2, 0.35, (170, 80, 190), 0.4)]),
    "night": ((2, 4, 12), (0, 0, 4), [(0.8, 0.3, 0.4, (40, 60, 140), 0.35), (0.95, 0.8, 0.3, (70, 40, 120), 0.2)]),
}


def local_properties():
    props = {}
    path = ROOT / "local.properties"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip()
    return props


def save(image, part):
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"wallpaper_{part}.jpg"
    image.convert("RGB").save(path, "JPEG", quality=88, optimize=True, progressive=True)
    print(f"{path.relative_to(ROOT)}  {path.stat().st_size // 1024} KB")


def fit_16x9(image):
    # Centre-crop to 16:9, then scale to 1920x1080
    w, h = image.size
    target_h = round(w * 9 / 16)
    if target_h <= h:
        top = (h - target_h) // 2
        image = image.crop((0, top, w, top + target_h))
    else:
        target_w = round(h * 16 / 9)
        left = (w - target_w) // 2
        image = image.crop((left, 0, left + target_w, h))
    return image.resize((WIDTH, HEIGHT), Image.LANCZOS)


def placeholder(part):
    top, bottom, blobs = PLACEHOLDER[part]
    y = np.linspace(0, 1, HEIGHT)[:, None, None]
    x = np.linspace(0, 1, WIDTH)[None, :, None]
    img = np.array(top, float) * (1 - y) + np.array(bottom, float) * y
    img = np.broadcast_to(img, (HEIGHT, WIDTH, 3)).copy()
    aspect = WIDTH / HEIGHT
    for bx, by, radius, colour, strength in blobs:
        d2 = ((x - bx) * aspect) ** 2 + (y - by) ** 2
        weight = strength * np.exp(-d2 / (2 * radius**2))
        img = img * (1 - weight) + np.array(colour, float) * weight
    # Fine grain so smooth gradients don't band on an 8-bit panel
    img += np.random.default_rng(7).normal(0, 1.2, img.shape)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def generate(part, key, model):
    body = json.dumps({
        "model": model,
        "prompt": f"{SCENE} {LIGHT[part]}",
        "size": "1536x1024",
        "quality": "high",
        "n": 1,
    }).encode()
    request = urllib.request.Request(
        "https://api.openai.com/v1/images/generations",
        data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            data = json.load(response)["data"][0]
    except urllib.error.HTTPError as e:
        # The error body never contains the key
        sys.exit(f"OpenAI error for {part}: {e.code} {e.read().decode(errors='replace')[:500]}")
    if "b64_json" in data:
        raw = base64.b64decode(data["b64_json"])
    else:
        with urllib.request.urlopen(data["url"], timeout=120) as response:
            raw = response.read()
    return fit_16x9(Image.open(io.BytesIO(raw)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--placeholder", action="store_true", help="gradient placeholders even if a key is set")
    parser.add_argument("--only", choices=list(LIGHT), help="just one part of the day")
    args = parser.parse_args()
    props = local_properties()
    key = props.get("openai.key", "")
    model = props.get("openai.image_model", "") or "gpt-image-1"
    for part in [args.only] if args.only else list(LIGHT):
        if key and not args.placeholder:
            print(f"Generating {part} with {model}...")
            save(generate(part, key, model), part)
        else:
            save(placeholder(part), part)


if __name__ == "__main__":
    main()
