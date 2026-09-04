#!/usr/bin/env python3
"""Genera el ícono base de Mirror Phone: teléfono con onda Wi-Fi.
Salida: apps/desktop/icon.png (1024x1024)."""
from PIL import Image, ImageDraw
from pathlib import Path

SIZE = 1024
OUT = Path(__file__).resolve().parent.parent / "apps" / "desktop" / "icon.png"

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))

# --- Fondo squircle con gradiente vertical (teal -> azul oscuro) ---
bg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
bgd = ImageDraw.Draw(bg, "RGBA")
top = (79, 209, 197)     # #4FD1C5
bot = (26, 32, 68)       # #1A2044
for y in range(SIZE):
    t = y / (SIZE - 1)
    r = int(top[0] + (bot[0] - top[0]) * t)
    g = int(top[1] + (bot[1] - top[1]) * t)
    b = int(top[2] + (bot[2] - top[2]) * t)
    bgd.line([(0, y), (SIZE, y)], fill=(r, g, b, 255))

mask = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(mask).rounded_rectangle(
    [(20, 20), (SIZE - 20, SIZE - 20)], radius=220, fill=255
)
img.paste(bg, (0, 0), mask)

# --- Teléfono blanco al centro ---
phone_w, phone_h = 360, 620
px = (SIZE - phone_w) // 2
py = (SIZE - phone_h) // 2 + 40

shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle(
    [(px + 8, py + 16), (px + phone_w + 8, py + phone_h + 16)],
    radius=52, fill=(0, 0, 0, 90),
)
img = Image.alpha_composite(img, shadow)
draw = ImageDraw.Draw(img, "RGBA")

draw.rounded_rectangle(
    [(px, py), (px + phone_w, py + phone_h)],
    radius=52, fill=(255, 255, 255, 255),
)
inset = 22
draw.rounded_rectangle(
    [(px + inset, py + inset + 20), (px + phone_w - inset, py + phone_h - inset - 20)],
    radius=34, fill=(15, 17, 21, 255),
)
notch_w = 120
draw.rounded_rectangle(
    [((SIZE - notch_w) // 2, py + 20),
     ((SIZE + notch_w) // 2, py + 46)],
    radius=13, fill=(15, 17, 21, 255),
)

# --- Onda Wi-Fi dentro de la pantalla ---
cx = SIZE // 2
cy = py + phone_h // 2 + 30
draw.ellipse([(cx - 22, cy + 130), (cx + 22, cy + 174)], fill=(79, 209, 197, 255))

def arc(radius, thick, color):
    bbox = [(cx - radius, cy - radius + 130), (cx + radius, cy + radius + 130)]
    draw.arc(bbox, start=200, end=340, fill=color, width=thick)

arc(70,  18, (79, 209, 197, 255))
arc(130, 22, (79, 209, 197, 220))
arc(200, 26, (79, 209, 197, 170))

img.save(OUT)
print(f"OK -> {OUT} ({SIZE}x{SIZE})")
