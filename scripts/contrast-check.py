#!/usr/bin/env python3
"""
contrast-check.py — WCAG contrast checker for the CyppieAgents design system.

WHY THIS EXISTS (CYP-656 → design-system rule): on an AGENT-COLOURED surface (titlebar), the
derived on-colour (`barContent`) sits at EXACTLY 4.5:1 by construction (`core/.../ColorDerivation.kt`
`deriveScheme`: on-colour = pure white/black, background nudged until it *just* reaches 4.5:1).
=> zero headroom => NO secondary/dimmed tone survives the (unbounded) agent-colour family.
State on such a surface is carried by a GLYPH at full contrast, never by tone/alpha.
See docs/COLOR-CODING.md §8. This script lets the next designer re-derive the numbers, not trust prose.

No dependencies. Python 3.

Usage:
  python3 scripts/contrast-check.py                 # self-test: reproduce the §8 invariant numbers
  python3 scripts/contrast-check.py FG BG           # contrast ratio of two #RRGGBB colours
  python3 scripts/contrast-check.py --dim FG BG A   # FG dimmed at alpha A over BG, vs BG (the "grey it out" test)
  python3 scripts/contrast-check.py --survives CAND f1 f2 ...   # does secondary CAND clear 4.5:1 vs every fill fi?
"""
import sys

AA_TEXT = 4.5   # WCAG 1.4.3 normal text
AA_LARGE = 3.0  # WCAG 1.4.3 large text / UI components


def _srgb_to_lin(c: float) -> float:
    c /= 255.0
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(rgb):
    r, g, b = rgb
    return 0.2126 * _srgb_to_lin(r) + 0.7152 * _srgb_to_lin(g) + 0.0722 * _srgb_to_lin(b)


def contrast(a, b) -> float:
    """WCAG contrast ratio in [1, 21], order-independent."""
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def parse(h: str):
    h = h.strip()
    if h.startswith("#"):
        h = h[1:]
    if h[:2].lower() == "0x":  # 0xRRGGBB / 0xAARRGGBB — strip the exact prefix (NOT lstrip: that eats leading 0s)
        h = h[2:]
    if len(h) == 8:  # AARRGGBB or RRGGBBAA — take the RGB (alpha handled separately by blend())
        h = h[2:] if h[:2].upper() == "FF" else h[:6]
    if len(h) != 6:
        raise ValueError(f"expected #RRGGBB, got {h!r}")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16))


def blend(fg, bg, a):  # fg over bg at alpha a (opaque result)
    return tuple(round(fg[i] * a + bg[i] * (1 - a)) for i in range(3))


def hexs(rgb):
    return "#%02X%02X%02X" % rgb


WHITE, BLACK = (255, 255, 255), (0, 0, 0)

# The 8 fixed identity slots (SenderPalette.kt) — avatarFill, on = white for all 8.
SLOTS = {
    "po": 0x3B3F8F, "teal": 0x1F7A6E, "violet": 0x6E4BD0, "magenta": 0xB5419A,
    "indigo": 0x3E63C0, "bronze": 0x9A6B2F, "pink": 0xC24D6A, "slate": 0x4C6A8A, "olive": 0x6E7A2E,
}


def self_test():
    print("=== agent-coloured-surface contrast invariant (docs/COLOR-CODING.md §8) ===\n")
    print("(1) barContent = pure WHITE on each fixed slot — headroom above 4.5:1 is tiny by construction:")
    for n, v in sorted(SLOTS.items(), key=lambda kv: contrast(WHITE, parse("#%06X" % kv[1]))):
        c = contrast(WHITE, parse("#%06X" % v))
        print(f"    {n:8} #{v:06X}  cr(white)={c:.2f}  headroom={c-AA_TEXT:+.2f}")
    print("    -> custom #RRGGBB agent colours land at EXACTLY ~4.5:1 (deriveScheme). Worst fixed slot: "
          f"pink +{contrast(WHITE, parse('#C24D6A'))-AA_TEXT:.2f}.\n")

    print("(2) ALPHA-DIM the on-colour over a bar tuned so white == ~4.5:1 -> collapses below AA:")
    bg = next((g, g, g) for g in range(256) if abs(contrast(WHITE, (g, g, g)) - AA_TEXT) < 0.03)
    print(f"    tuned bar bg {hexs(bg)}  cr(white,bg)={contrast(WHITE,bg):.2f}")
    for a in (1.0, 0.90, 0.85, 0.75, 0.60):
        fg = blend(WHITE, bg, a)
        c = contrast(fg, bg)
        print(f"    white @alpha {a:.2f} -> {hexs(fg)}  cr={c:.2f}  {'PASS' if c>=AA_TEXT else 'FAIL <4.5'}")
    print()

    print("(3) a FIXED 'stale grey' token vs an arbitrary agent-colour family -> fails most members:")
    greys = {"onSurfaceVariant-light": 0x3A4E5A, "onSurfaceVariant-dark": 0xA6BECD,
             "neutral-#9E9E9E": 0x9E9E9E, "neutral-#BDBDBD": 0xBDBDBD}
    fam = {"dark-teal #1F7A6E": 0x1F7A6E, "light #7FBEEA": 0x7FBEEA, "mid #C24D6A": 0xC24D6A,
           "pale #E4EFF8": 0xE4EFF8, "olive #6E7A2E": 0x6E7A2E}
    for gn, gv in greys.items():
        cells = []
        for fn, fv in fam.items():
            c = contrast(parse("#%06X" % gv), parse("#%06X" % fv))
            cells.append(f"{fn.split()[0]}:{c:.2f}{'' if c>=AA_TEXT else '✗'}")
        print(f"    {gn:22} -> " + "  ".join(cells))
    print()

    print("(4) SCOPE: the invariant is ONLY for agent-coloured surfaces. A guaranteed-headroom surface")
    print("    (standard M3 surface) is fine for a secondary tone — e.g. onSurfaceVariant on surface:")
    for tn, surf in (("maritime-light surface #FFFFFF", 0xFFFFFF), ("maritime-dark surface #06121A", 0x06121A)):
        osv = 0x3A4E5A if surf == 0xFFFFFF else 0xA6BECD
        c = contrast(parse("#%06X" % osv), parse("#%06X" % surf))
        print(f"    onSurfaceVariant vs {tn}: {c:.2f}  {'PASS (secondary tone OK here)' if c>=AA_TEXT else 'FAIL'}")
    print("\n=> Rule: agent-coloured surface -> glyph at full contrast (never tone/alpha). "
          "Guaranteed-headroom surface -> secondary tone is fine.")


def main(argv):
    if len(argv) == 0:
        self_test(); return 0
    if argv[0] == "--dim" and len(argv) == 4:
        fg, bg, a = parse(argv[1]), parse(argv[2]), float(argv[3])
        blended = blend(fg, bg, a)
        c = contrast(blended, bg)
        print(f"{hexs(fg)} @alpha {a} over {hexs(bg)} = {hexs(blended)}  cr={c:.2f}  "
              f"{'PASS' if c>=AA_TEXT else 'FAIL <4.5'}")
        return 0 if c >= AA_TEXT else 1
    if argv[0] == "--survives" and len(argv) >= 3:
        cand = parse(argv[1]); fills = [parse(x) for x in argv[2:]]
        ok = True
        for f in fills:
            c = contrast(cand, f)
            if c < AA_TEXT:
                ok = False
            print(f"  {hexs(cand)} vs {hexs(f)}: {c:.2f}  {'PASS' if c>=AA_TEXT else 'FAIL <4.5'}")
        print(f"=> {'survives the family' if ok else 'FAILS at least one member — do not use a fixed tone here'}")
        return 0 if ok else 1
    if len(argv) == 2:
        c = contrast(parse(argv[0]), parse(argv[1]))
        print(f"cr({argv[0]}, {argv[1]}) = {c:.2f}  "
              f"(AA text {'PASS' if c>=AA_TEXT else 'FAIL'} · AA large {'PASS' if c>=AA_LARGE else 'FAIL'})")
        return 0 if c >= AA_TEXT else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
