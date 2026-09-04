#!/usr/bin/env python3
"""Genera los mipmap PNG legacy del launcher para Android desde nuestro logo."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "apps/android/app/src/main/res"

# Tamaños oficiales del launcher (px) para densidades mdpi..xxxhdpi
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Colores de marca
TEAL = (20, 184, 166)
DARK = (15, 23, 42)
WHITE = (255, 255, 255)


def draw_logo(size: int) -> Image.Image:
    """Dibuja el logo (teléfono + onda Wi-Fi) sobre fondo squircle con gradiente."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Fondo squircle con gradiente vertical teal → dark
    for y in range(size):
        t = y / size
        r = int(TEAL[0] * (1 - t) + DARK[0] * t)
        g = int(TEAL[1] * (1 - t) + DARK[1] * t)
        b = int(TEAL[2] * (1 - t) + DARK[2] * t)
        d.line([(0, y), (size, y)], fill=(r, g, b, 255))

    # Máscara squircle (esquinas suaves)
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    radius = int(size * 0.22)
    md.rounded_rectangle([0, 0, size, size], radius=radius, fill=255)
    img.putalpha(mask)

    # Teléfono (rectángulo blanco redondeado, centrado, un poco más chico)
    ph_w = int(size * 0.42)
    ph_h = int(size * 0.62)
    ph_x = (size - ph_w) // 2
    ph_y = int(size * 0.28)
    ph_r = int(ph_w * 0.18)
    d.rounded_rectangle(
        [ph_x, ph_y, ph_x + ph_w, ph_y + ph_h],
        radius=ph_r,
        fill=WHITE,
    )
    # Pantalla interior teal
    inset = int(size * 0.03)
    scr_r = max(2, ph_r - inset)
    d.rounded_rectangle(
        [ph_x + inset, ph_y + inset, ph_x + ph_w - inset, ph_y + ph_h - inset],
        radius=scr_r,
        fill=TEAL,
    )
    # Notch (una raya finita arriba)
    notch_w = int(ph_w * 0.35)
    notch_h = max(2, int(size * 0.012))
    notch_x = ph_x + (ph_w - notch_w) // 2
    notch_y = ph_y + int(size * 0.03)
    d.rounded_rectangle(
        [notch_x, notch_y, notch_x + notch_w, notch_y + notch_h],
        radius=notch_h // 2,
        fill=WHITE,
    )

    # Ondas Wi-Fi arriba del teléfono, centradas
    cx = size // 2
    cy = ph_y - int(size * 0.02)
    # Tres arcos concéntricos
    for i, radius in enumerate([int(size * 0.09), int(size * 0.15), int(size * 0.22)]):
        w = max(2, int(size * 0.025))
        bbox = [cx - radius, cy - radius, cx + radius, cy + radius]
        # Arco superior
        d.arc(bbox, start=210, end=330, fill=WHITE, width=w)
    # Punto central
    dot_r = max(2, int(size * 0.018))
    d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=WHITE)

    return img


def draw_foreground(size: int) -> Image.Image:
    """Foreground para adaptive icon: solo el logo, sin fondo, con padding safe zone.
    Android recorta el ícono adaptativo; el arte debe caber en el 66% central."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Zona segura: dibujar dentro del 66% central
    inner = int(size * 0.66)
    off = (size - inner) // 2

    # Teléfono
    ph_w = int(inner * 0.55)
    ph_h = int(inner * 0.75)
    ph_x = off + (inner - ph_w) // 2
    ph_y = off + int(inner * 0.20)
    ph_r = int(ph_w * 0.18)
    d.rounded_rectangle(
        [ph_x, ph_y, ph_x + ph_w, ph_y + ph_h],
        radius=ph_r,
        fill=WHITE,
    )
    inset = int(inner * 0.03)
    scr_r = max(2, ph_r - inset)
    d.rounded_rectangle(
        [ph_x + inset, ph_y + inset, ph_x + ph_w - inset, ph_y + ph_h - inset],
        radius=scr_r,
        fill=TEAL,
    )
    # Notch
    notch_w = int(ph_w * 0.35)
    notch_h = max(2, int(inner * 0.012))
    notch_x = ph_x + (ph_w - notch_w) // 2
    notch_y = ph_y + int(inner * 0.03)
    d.rounded_rectangle(
        [notch_x, notch_y, notch_x + notch_w, notch_y + notch_h],
        radius=notch_h // 2,
        fill=WHITE,
    )

    # Wi-Fi
    cx = size // 2
    cy = ph_y - int(inner * 0.02)
    for radius in [int(inner * 0.11), int(inner * 0.18), int(inner * 0.26)]:
        w = max(2, int(inner * 0.028))
        bbox = [cx - radius, cy - radius, cx + radius, cy + radius]
        d.arc(bbox, start=210, end=330, fill=WHITE, width=w)
    dot_r = max(2, int(inner * 0.02))
    d.ellipse([cx - dot_r, cy - dot_r, cx + dot_r, cy + dot_r], fill=WHITE)

    return img


def round_square(img: Image.Image) -> Image.Image:
    """Convierte una squircle en circular para ic_launcher_round."""
    size = img.size[0]
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([0, 0, size, size], fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def main():
    for name, size in DENSITIES.items():
        outdir = RES / name
        outdir.mkdir(parents=True, exist_ok=True)
        logo = draw_logo(size)
        logo.save(outdir / "ic_launcher.png")
        round_square(logo).save(outdir / "ic_launcher_round.png")
        # Foreground para adaptive (tamaño 108/48 * size ≈ 2.25x)
        fg_size = int(size * 108 / 48)
        fg = draw_foreground(fg_size)
        fg.save(outdir / "ic_launcher_foreground.png")
        print(f"  {name}: {size}px legacy + {fg_size}px foreground")

    # También guardar un icon.png grande para referencia
    big = draw_logo(1024)
    big.save(ROOT / "assets/android_icon_1024.png") if (ROOT / "assets").exists() else None

    print("Listo ✅")


if __name__ == "__main__":
    main()
