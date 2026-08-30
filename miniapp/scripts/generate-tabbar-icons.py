"""生成微信原生 Tabbar 的普通/选中 PNG 图标。"""

from pathlib import Path

from PIL import Image, ImageDraw


SIZE = 81
SCALE = 4
STROKE = 5 * SCALE
COLORS = {"": "#8B94A7", "-active": "#2E7CF6"}
OUT = Path(__file__).resolve().parents[1] / "src" / "static" / "tabbar"


def canvas():
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (255, 255, 255, 0))
    return image, ImageDraw.Draw(image)


def point(value):
    return round(value * SCALE)


def home(color):
    image, draw = canvas()
    draw.line([(point(17), point(39)), (point(40.5), point(18)), (point(64), point(39))], fill=color, width=STROKE, joint="curve")
    draw.line([(point(23), point(36)), (point(23), point(64)), (point(58), point(64)), (point(58), point(36))], fill=color, width=STROKE, joint="curve")
    draw.line([(point(35), point(64)), (point(35), point(48)), (point(47), point(48)), (point(47), point(64))], fill=color, width=STROKE)
    return image


def order(color):
    image, draw = canvas()
    draw.rounded_rectangle((point(18), point(13), point(63), point(68)), radius=point(8), outline=color, width=STROKE)
    for y in (29, 41, 53):
        draw.ellipse((point(25), point(y - 2), point(29), point(y + 2)), fill=color)
        draw.line([(point(35), point(y)), (point(55), point(y))], fill=color, width=STROKE)
    return image


def profile(color):
    image, draw = canvas()
    draw.ellipse((point(28), point(13), point(53), point(38)), outline=color, width=STROKE)
    draw.arc((point(16), point(35), point(65), point(76)), start=194, end=346, fill=color, width=STROKE)
    draw.line([(point(18), point(61)), (point(18), point(65)), (point(63), point(65)), (point(63), point(61))], fill=color, width=STROKE)
    return image


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    painters = {"home": home, "order": order, "profile": profile}
    for name, painter in painters.items():
        for suffix, color in COLORS.items():
            image = painter(color).resize((SIZE, SIZE), Image.Resampling.LANCZOS)
            image.save(OUT / f"{name}{suffix}.png", optimize=True)


if __name__ == "__main__":
    main()
