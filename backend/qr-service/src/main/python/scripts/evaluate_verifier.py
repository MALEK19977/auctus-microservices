"""Evaluate cheque_verifier on genuine cheques and on tampered copies.

Tampering is simulated by repainting a printed field on the cheque image so that it
no longer agrees with the QR code - exactly the fraud the QR is meant to expose.

Usage: python evaluate_verifier.py [n_cheques]
"""
import csv
import os
import sys
from datetime import date

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cheque_verifier as cv_mod

IMAGES = "C:/Users/21695/OneDrive/Desktop/cheque_generator/output/images"
CSV = "C:/Users/21695/OneDrive/Desktop/cheque_generator/output/cheques.csv"
AS_OF = date(2026, 7, 1)  # inside the validity window of the generated cheques


def repaint(image, box, text, bold=True):
    """Paint over a printed field with a different value."""
    x, y, w, h = box
    out = image.copy()
    patch_colour = out[y + h // 2, x - 6].tolist() if x >= 6 else [255, 255, 255]
    cv2.rectangle(out, (x, y), (x + w, y + h), patch_colour, -1)
    pil = Image.fromarray(cv2.cvtColor(out, cv2.COLOR_BGR2RGB))
    font_path = "C:/Windows/Fonts/Arialbd.ttf" if bold else "C:/Windows/Fonts/Arial.ttf"
    try:
        font = ImageFont.truetype(font_path, 16 if bold else 14)
    except OSError:
        font = ImageFont.load_default()
    ImageDraw.Draw(pil).text((x + 4, y + 4), text, fill=(0, 0, 0), font=font)
    return cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)


TAMPERS = {
    "cheque_number": lambda img, row: repaint(img, (660, 82, 104, 24), "9999999"),
    "plafond":       lambda img, row: repaint(img, (660, 110, 150, 26), "DT         99000"),
    "titulaire":     lambda img, row: repaint(img, (579, 392, 250, 26), "SOCIETE FRAUDE"),
    "expiry_date":   lambda img, row: repaint(img, (637, 477, 106, 24), "31/12/2099", bold=False),
}


def statuses(result):
    return {c["name"]: c["status"] for c in result.get("checks", [])}


def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    rows = list(csv.DictReader(open(CSV, encoding="utf-8-sig")))[:limit]

    genuine_ok = genuine_total = 0
    caught = {k: [0, 0] for k in TAMPERS}

    for index, row in enumerate(rows):
        path = "%s/zitouna_%04d_%s.png" % (IMAGES, index + 1, row["signature_dossier"])
        image = cv2.imread(path)
        if image is None:
            continue

        genuine_total += 1
        result = cv_mod.verify(path, today=AS_OF)
        st = statuses(result)
        qr_checks = ["cheque_number", "rib", "titulaire", "plafond", "expiry_date", "not_expired"]
        clean = all(st.get(k) == "PASS" for k in qr_checks)
        genuine_ok += clean
        if not clean:
            bad = {k: v for k, v in st.items() if k in qr_checks and v != "PASS"}
            print("  genuine cheque %d not clean: %s" % (index + 1, bad))

        for name, tamper in TAMPERS.items():
            tampered_path = os.path.join(os.environ.get("TEMP", "."), "tampered_%s.png" % name)
            cv2.imwrite(tampered_path, tamper(image, row))
            res = cv_mod.verify(tampered_path, today=AS_OF)
            st2 = statuses(res)
            caught[name][1] += 1
            # The tampered field must be flagged, and the overall verdict must not be ACCEPTED.
            if st2.get(name) == "FAIL" and res.get("verdict") != "ACCEPTED":
                caught[name][0] += 1
            os.remove(tampered_path)

    print("\n" + "=" * 60)
    print("genuine cheques with all QR checks passing: %d/%d" % (genuine_ok, genuine_total))
    print("-" * 60)
    for name, (hit, total) in caught.items():
        print("  tampered %-15s detected %d/%d" % (name, hit, total))
    print("=" * 60)


if __name__ == "__main__":
    main()
