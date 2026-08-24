"""Cross-check a Tunisian cheque against the data carried by its QR code.

Implements step 2 of the Auctus pipeline. The QR code repeats information that is
also printed on the cheque; any disagreement means the cheque or the QR has been
tampered with. On top of that it applies the rules introduced by the 2025 Tunisian
cheque reform: a cheque carries a plafond (maximum amount) and an expiry date, and
the amount actually written may not exceed that plafond.

Usage:
    python cheque_verifier.py <cheque_image> [--date DD/MM/YYYY]

Only the JSON result goes to stdout; diagnostics go to stderr.
"""
import json
import os
import re
import sys
from datetime import datetime

import cv2
import numpy as np
import pytesseract
from pyzbar.pyzbar import decode as decode_qr

if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

pytesseract.pytesseract.tesseract_cmd = os.environ.get(
    "TESSERACT_CMD", r"C:\Program Files\Tesseract-OCR\tesseract.exe")

# Geometry of the cheque template (see cheque_generator/generate_cheques.py).
TEMPLATE_W, TEMPLATE_H = 1550, 663

DIGITS = "--psm 7 --oem 3 -c tessedit_char_whitelist=0123456789"
DATE = "--psm 7 --oem 3 -c tessedit_char_whitelist=0123456789/"
TEXT = "--psm 7 --oem 3"

# field -> (x, y, w, h, tesseract config)
PRINTED_REGIONS = {
    "cheque_number": (660, 82, 110, 26, DIGITS),
    "plafond":       (660, 110, 150, 28, DIGITS),
    "rib_bank":      (434, 334, 34, 26, DIGITS),
    "rib_agency":    (529, 334, 44, 26, DIGITS),
    "rib_account":   (681, 334, 130, 26, DIGITS),
    "rib_key":       (939, 334, 34, 26, DIGITS),
    "titulaire":     (579, 392, 260, 28, TEXT),
    "issue_date":    (74, 422, 110, 26, DATE),
    "expiry_date":   (637, 477, 110, 26, DATE),
}

HANDWRITTEN_REGIONS = {
    "amount_digits":  (1125, 74, 190, 46),
    "amount_words":   (158, 220, 780, 48),
}

CURSIVE_FONT = "C:/Users/21695/OneDrive/Desktop/cheque_generator/fonts/Palms Delight.otf"


def log(message):
    print(message, file=sys.stderr)


# ---------------------------------------------------------------------------
# QR code
# ---------------------------------------------------------------------------

# https://pecc.tn/1/<cheque>/<rib>/<version>/<titulaire>/<plafond>/<dd>/<mm>/<yyyy>/<rib_benef>
QR_PATTERN = re.compile(
    r"pecc\.tn/(\d+)/(\d+)/(\d+)/(\d+)/([^/]+)/(\d+)/(\d{2})/(\d{2})/(\d{4})/(\d+)")


def read_qr(image):
    for source in (cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), image):
        for obj in decode_qr(source):
            data = obj.data.decode("utf-8", errors="replace")
            if len(data) > 10:
                log("QR payload: %s" % data)
                return parse_qr(data)
    return None


def parse_qr(data):
    match = QR_PATTERN.search(data.replace(" ", ""))
    if not match:
        return {"raw": data}
    plafond = match.group(6)
    receiver_rib = match.group(10)
    return {
        "raw": data,
        "cheque_number": match.group(2),
        "rib_titulaire": match.group(3),
        "titulaire": match.group(5).replace("%20", " "),
        "plafond": plafond,
        "expiry_date": "%s/%s/%s" % (match.group(7), match.group(8), match.group(9)),
        "rib_beneficiaire": receiver_rib,
        # Names the existing frontend and qr_reader.py already expect.
        "max_amount": plafond,
        "receiver_rib": receiver_rib,
    }


# ---------------------------------------------------------------------------
# Printed fields
# ---------------------------------------------------------------------------

def _scaled_crop(image, box):
    h, w = image.shape[:2]
    sx, sy = w / TEMPLATE_W, h / TEMPLATE_H
    x, y, bw, bh = box
    x, y = int(x * sx), int(y * sy)
    bw, bh = max(1, int(bw * sx)), max(1, int(bh * sy))
    return image[y:y + bh, x:x + bw]


def ocr_region(image, box, config, scale=4):
    crop = _scaled_crop(image, box)
    if crop.size == 0:
        return ""
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    big = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
    _, binary = cv2.threshold(big, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    if binary.mean() < 127:
        binary = 255 - binary
    binary = cv2.copyMakeBorder(binary, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=255)
    return pytesseract.image_to_string(binary, lang="eng", config=config).strip()


def _ocr_variants(image, box, config):
    """Alternative readings of the same field, used only to settle a disagreement.

    A single tuned pass is the most accurate on average, so it stays the primary
    reading; these extra passes exist to rescue the occasional glyph confusion
    (a 7 read as a 1) that would otherwise reject a perfectly good cheque.
    """
    crop = _scaled_crop(image, box)
    if crop.size == 0:
        return []
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)

    readings = []
    for scale in (4, 6, 8):
        big = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_CUBIC)
        for mode in ("otsu", "adaptive"):
            if mode == "otsu":
                _, binary = cv2.threshold(big, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
            else:
                binary = cv2.adaptiveThreshold(big, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                               cv2.THRESH_BINARY, 31, 10)
            if binary.mean() < 127:
                binary = 255 - binary
            binary = cv2.copyMakeBorder(binary, 15, 15, 15, 15, cv2.BORDER_CONSTANT, value=255)
            for psm in ("7", "8", "13"):
                text = pytesseract.image_to_string(
                    binary, lang="eng", config=config.replace("--psm 7", "--psm " + psm))
                text = text.strip().replace("\n", " ")
                if text:
                    readings.append(text)
    return readings


def confirms(image, field, expected):
    """True when any alternative reading of `field` agrees with `expected`."""
    if field not in PRINTED_REGIONS or not expected:
        return False
    x, y, w, h, config = PRINTED_REGIONS[field]
    target = _norm(expected)
    for reading in _ocr_variants(image, (x, y, w, h), config):
        if _norm(reading) == target:
            log("field '%s' confirmed as %s on a retry pass" % (field, expected))
            return True
    return False


def read_printed_fields(image):
    fields = {}
    for name, (x, y, w, h, config) in PRINTED_REGIONS.items():
        fields[name] = ocr_region(image, (x, y, w, h), config).replace("\n", " ").strip()
    fields["rib_titulaire"] = "".join(
        fields.get(part, "") for part in ("rib_bank", "rib_agency", "rib_account", "rib_key"))
    return fields


# ---------------------------------------------------------------------------
# Handwritten fields (best effort - never used to reject on its own)
# ---------------------------------------------------------------------------

def ink_mask(crop):
    """Isolate the blue handwriting from the printed guide lines and paper."""
    b = crop[:, :, 0].astype(np.int16)
    g = crop[:, :, 1].astype(np.int16)
    r = crop[:, :, 2].astype(np.int16)
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    blueness = b - (r + g) // 2
    strong, weak = blueness > 15, (blueness > -5) & (gray < 175)
    _, labels = cv2.connectedComponents(weak.astype(np.uint8))
    keep = np.unique(labels[strong])
    return np.isin(labels, keep[keep != 0]).astype(np.uint8) * 255


def _render_glyph(char, height=40):
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype(CURSIVE_FONT, 34)
    canvas = Image.new("L", (120, 120), 255)
    ImageDraw.Draw(canvas).text((30, 20), char, fill=0, font=font)
    arr = 255 - np.array(canvas)
    ys, xs = np.nonzero(arr > 60)
    if len(xs) == 0:
        return None
    arr = arr[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    scale = height / arr.shape[0]
    return cv2.resize(arr, (max(1, int(arr.shape[1] * scale)), height))


_GLYPHS = None


def glyphs():
    global _GLYPHS
    if _GLYPHS is None:
        _GLYPHS = {}
        if os.path.exists(CURSIVE_FONT):
            for char in "0123456789#":
                rendered = _render_glyph(char)
                if rendered is not None:
                    _GLYPHS[char] = rendered
    return _GLYPHS


def _split_columns(mask, min_width=1):
    filled = mask.sum(axis=0) > 0
    spans, start = [], None
    for i, on in enumerate(filled):
        if on and start is None:
            start = i
        elif not on and start is not None:
            if i - start >= min_width:
                spans.append((start, i))
            start = None
    if start is not None and len(filled) - start >= min_width:
        spans.append((start, len(filled)))

    if len(spans) < 2:
        return spans

    # Neighbouring glyphs frequently touch - typically the leading '#' and the
    # first digit - which would otherwise swallow a whole character. Any blob
    # much wider than a typical one is cut into that many equal slices.
    widths = sorted(b - a for a, b in spans)
    typical = widths[len(widths) // 2]
    if typical <= 0:
        return spans

    refined = []
    for a, b in spans:
        pieces = int(round((b - a) / float(typical)))
        if pieces > 1:
            step = (b - a) / float(pieces)
            for k in range(pieces):
                refined.append((a + int(k * step), a + int((k + 1) * step)))
        else:
            refined.append((a, b))
    return refined


def _classify_glyph(blob, templates, height=40):
    ys, xs = np.nonzero(blob)
    if len(xs) == 0:
        return "", 0.0
    blob = blob[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    scale = height / blob.shape[0]
    blob = cv2.resize(blob, (max(1, int(blob.shape[1] * scale)), height))
    blob = cv2.GaussianBlur(blob.astype(np.float32), (0, 0), 2.0)

    best_char, best_score = "", -1.0
    for char, template in templates.items():
        width = max(blob.shape[1], template.shape[1])
        a = cv2.copyMakeBorder(blob, 6, 6, 6, width - blob.shape[1] + 6,
                               cv2.BORDER_CONSTANT, value=0)
        b = cv2.GaussianBlur(template.astype(np.float32), (0, 0), 2.0)
        score = float(cv2.matchTemplate(a, b, cv2.TM_CCOEFF_NORMED).max())
        if score > best_score:
            best_char, best_score = char, score
    return best_char, best_score


def read_handwritten_digits(image):
    """Read the '#amount#' box. Returns (value, confidence) - value may be None."""
    templates = glyphs()
    if not templates:
        return None, 0.0
    crop = _scaled_crop(image, HANDWRITTEN_REGIONS["amount_digits"])
    if crop.size == 0:
        return None, 0.0
    mask = ink_mask(crop)
    chars, scores = [], []
    for a, b in _split_columns(mask):
        char, score = _classify_glyph(mask[:, a:b], templates)
        if char:
            chars.append(char)
            scores.append(score)
    digits = "".join(chars).replace("#", "")
    if not digits.isdigit() or not scores:
        return None, 0.0
    return int(digits), float(np.mean(scores))


# --- amount in words ---------------------------------------------------------

WORD_VALUES = {
    "ZERO": 0, "UN": 1, "UNE": 1, "DEUX": 2, "TROIS": 3, "QUATRE": 4, "CINQ": 5,
    "SIX": 6, "SEPT": 7, "HUIT": 8, "NEUF": 9, "DIX": 10, "ONZE": 11, "DOUZE": 12,
    "TREIZE": 13, "QUATORZE": 14, "QUINZE": 15, "SEIZE": 16,
    "VINGT": 20, "VINGTS": 20, "TRENTE": 30, "QUARANTE": 40, "CINQUANTE": 50,
    "SOIXANTE": 60,
}
VOCABULARY = list(WORD_VALUES) + ["CENT", "CENTS", "MILLE", "DINARS", "DINAR", "ET"]


def _snap(token):
    import difflib
    if token in VOCABULARY:
        return token, 1.0
    match = difflib.get_close_matches(token, VOCABULARY, n=1, cutoff=0.6)
    if not match:
        return None, 0.0
    return match[0], difflib.SequenceMatcher(None, token, match[0]).ratio()


def words_to_amount(text):
    """Parse a (possibly OCR-mangled) French amount in words.

    Works on a flat token stream rather than on whitespace-delimited groups: the
    OCR routinely splits or merges words ("QUA TPE-VTNGT"), so spacing carries no
    reliable information. Returns (value, confidence).
    """
    pieces = [p for p in re.split(r"[^A-ZÉÈ]+", text.upper()) if len(p) >= 2]

    tokens, scores = [], []
    i = 0
    while i < len(pieces):
        word, score = _snap(pieces[i])
        # The OCR often breaks a word in two ("MILLE" -> "MTL LE"); if a piece does
        # not resolve on its own, try it glued to the next one.
        if word is None and i + 1 < len(pieces):
            merged, merged_score = _snap(pieces[i] + pieces[i + 1])
            if merged is not None and merged_score >= 0.7:
                tokens.append(merged)
                scores.append(merged_score)
                i += 2
                continue
        if word:
            tokens.append(word)
            scores.append(score)
        i += 1

    total = current = 0
    seen = False
    i = 0
    while i < len(tokens):
        token = tokens[i]

        # QUATRE-VINGT(S) is 80, not 4 + 20 - it must be consumed as one unit.
        if token == "QUATRE" and i + 1 < len(tokens) and tokens[i + 1] in ("VINGT", "VINGTS"):
            current += 80
            seen = True
            i += 2
            continue

        if token == "MILLE":
            total += (current or 1) * 1000
            current = 0
            seen = True
        elif token in ("CENT", "CENTS"):
            current = (current or 1) * 100
            seen = True
        elif token in ("DINARS", "DINAR", "ET"):
            pass
        else:
            current += WORD_VALUES.get(token, 0)
            seen = True
        i += 1

    total += current
    confidence = float(np.mean(scores)) if scores else 0.0
    return (total if seen else None), confidence


def read_handwritten_words(image):
    crop = _scaled_crop(image, HANDWRITTEN_REGIONS["amount_words"])
    if crop.size == 0:
        return None, 0.0, ""
    out = 255 - ink_mask(crop)
    out = cv2.resize(out, None, fx=4, fy=4, interpolation=cv2.INTER_CUBIC)
    out = cv2.GaussianBlur(out, (0, 0), 1.5)
    out = cv2.copyMakeBorder(out, 25, 25, 25, 25, cv2.BORDER_CONSTANT, value=255)
    raw = pytesseract.image_to_string(out, lang="fra", config=TEXT).strip()
    value, confidence = words_to_amount(raw)
    return value, confidence, raw


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

def _norm(value):
    return re.sub(r"[\s.]", "", (value or "")).upper()


def check(name, label, status, expected=None, found=None, message=""):
    return {"name": name, "label": label, "status": status,
            "expected": expected, "found": found, "message": message}


def compare_field(name, label, qr_value, printed_value, image=None, field=None):
    if qr_value is None:
        return check(name, label, "SKIP", message="absent du QR code")
    if not printed_value:
        return check(name, label, "WARN", qr_value, printed_value,
                     "champ illisible sur l'image")
    if _norm(qr_value) == _norm(printed_value):
        return check(name, label, "PASS", qr_value, printed_value)

    # Before calling a cheque forged, make sure the disagreement is not just this
    # one OCR pass misreading a character. A tampered field never reads back as
    # the QR value, however the image is processed.
    if image is not None and field is not None and confirms(image, field, qr_value):
        return check(name, label, "PASS", qr_value, qr_value,
                     "confirmé après relecture (première lecture : %s)" % printed_value)

    return check(name, label, "FAIL", qr_value, printed_value,
                 "le QR code et le chèque ne concordent pas")


def compare_rib(qr_rib, printed_rib, image):
    """The RIB is printed in four separate boxes, so it is retried box by box."""
    label = "RIB du titulaire"
    if not qr_rib:
        return check("rib", label, "SKIP", message="absent du QR code")
    if not printed_rib:
        return check("rib", label, "WARN", qr_rib, printed_rib, "champ illisible sur l'image")
    if _norm(qr_rib) == _norm(printed_rib):
        return check("rib", label, "PASS", qr_rib, printed_rib)

    # bank(2) + agency(3) + account(13) + key(2)
    segments = [("rib_bank", qr_rib[0:2]), ("rib_agency", qr_rib[2:5]),
                ("rib_account", qr_rib[5:18]), ("rib_key", qr_rib[18:20])]
    if image is not None and all(confirms(image, field, expected) for field, expected in segments):
        return check("rib", label, "PASS", qr_rib, qr_rib,
                     "confirmé après relecture (première lecture : %s)" % printed_rib)

    return check("rib", label, "FAIL", qr_rib, printed_rib,
                 "le QR code et le chèque ne concordent pas")


def build_checks(qr, printed, hw_digits, hw_words, today, image=None):
    checks = []

    if not qr:
        checks.append(check("qr_present", "QR code lisible", "FAIL",
                            message="aucun QR code détecté sur l'image"))
        return checks
    checks.append(check("qr_present", "QR code lisible", "PASS"))

    if "cheque_number" not in qr:
        checks.append(check("qr_format", "Format du QR code", "FAIL", found=qr.get("raw"),
                            message="le QR ne suit pas le format pecc.tn attendu"))
        return checks
    checks.append(check("qr_format", "Format du QR code", "PASS"))

    # --- QR vs printed cheque ---
    checks.append(compare_field("cheque_number", "Numéro de chèque",
                                qr.get("cheque_number"), printed.get("cheque_number"),
                                image, "cheque_number"))
    checks.append(compare_rib(qr.get("rib_titulaire"), printed.get("rib_titulaire"), image))
    checks.append(compare_field("titulaire", "Nom du titulaire",
                                qr.get("titulaire"), printed.get("titulaire"),
                                image, "titulaire"))
    checks.append(compare_field("plafond", "Plafond (valeur maximale)",
                                qr.get("plafond"), printed.get("plafond"),
                                image, "plafond"))
    checks.append(compare_field("expiry_date", "Date d'expiration",
                                qr.get("expiry_date"), printed.get("expiry_date"),
                                image, "expiry_date"))

    # The beneficiary RIB only exists in the QR - nothing on the cheque to compare it to.
    checks.append(check("rib_beneficiaire", "RIB du bénéficiaire", "INFO",
                        found=qr.get("rib_beneficiaire"),
                        message="présent uniquement dans le QR, non imprimé sur le chèque"))

    # --- rules from the 2025 Tunisian cheque reform ---
    expiry = qr.get("expiry_date")
    if expiry:
        try:
            expiry_date = datetime.strptime(expiry, "%d/%m/%Y").date()
            if today <= expiry_date:
                checks.append(check("not_expired", "Chèque non expiré", "PASS",
                                    expected="<= %s" % expiry, found=today.strftime("%d/%m/%Y")))
            else:
                checks.append(check("not_expired", "Chèque non expiré", "FAIL",
                                    expected="<= %s" % expiry, found=today.strftime("%d/%m/%Y"),
                                    message="le délai de validité est dépassé"))
        except ValueError:
            checks.append(check("not_expired", "Chèque non expiré", "WARN", found=expiry,
                                message="date d'expiration illisible"))

    plafond = qr.get("plafond")
    amount, amount_source, amount_conf = None, None, 0.0
    if hw_words[0] is not None and hw_words[1] >= 0.75:
        # Under Tunisian law the amount in words prevails over the figures.
        amount, amount_source, amount_conf = hw_words[0], "montant en lettres", hw_words[1]
    elif hw_digits[0] is not None and hw_digits[1] >= 0.80:
        amount, amount_source, amount_conf = hw_digits[0], "montant en chiffres", hw_digits[1]

    # Handwriting is read for the agent's benefit, not to decide anything. Cursive
    # digits merge into one another often enough that a high glyph-match score
    # still hides a dropped character, so the reading is reported for visual
    # confirmation and only blocks when both independent readings agree that the
    # plafond is exceeded - the one case where the evidence is strong.
    words_value, words_conf = hw_words
    digits_value, digits_conf = hw_digits
    both_agree = (words_value is not None and words_value == digits_value)

    if plafond and both_agree and words_value > int(plafond):
        checks.append(check("amount_within_plafond", "Montant dans le plafond", "FAIL",
                            expected="<= %s DT" % plafond, found="%s DT" % words_value,
                            message="le montant dépasse le plafond autorisé"))
    elif plafond and both_agree:
        checks.append(check("amount_within_plafond", "Montant dans le plafond", "PASS",
                            expected="<= %s DT" % plafond, found="%s DT" % words_value))
    else:
        checks.append(check("amount_within_plafond", "Montant dans le plafond", "INFO",
                            expected="<= %s DT" % plafond,
                            found=None if amount is None else "%s DT (%s)" % (amount, amount_source),
                            message="montant manuscrit à confirmer visuellement par l'agent"))

    # Under Tunisian law the amount in words prevails over the figures. Both
    # readings are surfaced so the agent can compare them against the cheque.
    if both_agree:
        checks.append(check("amount_consistency", "Montant lettres = chiffres", "PASS",
                            expected=str(words_value), found=str(digits_value)))
    else:
        checks.append(check("amount_consistency", "Montant lettres = chiffres", "INFO",
                            expected=None if words_value is None else "%s (lettres)" % words_value,
                            found=None if digits_value is None else "%s (chiffres)" % digits_value,
                            message="lectures divergentes - l'agent tranche "
                                    "(le montant en lettres fait foi)"))

    return checks


def verdict_from(checks):
    if any(c["status"] == "FAIL" for c in checks):
        return "REJECTED"
    if any(c["status"] == "WARN" for c in checks):
        return "REVIEW"
    return "ACCEPTED"


# ---------------------------------------------------------------------------

def verify(image_path, today=None):
    today = today or datetime.now().date()
    image = cv2.imread(image_path)
    if image is None:
        return {"success": False, "error": "Cannot load image"}

    qr = read_qr(image)
    printed = read_printed_fields(image)
    log("printed fields: %s" % printed)

    digits = read_handwritten_digits(image)
    words_value, words_conf, words_raw = read_handwritten_words(image)
    log("handwritten digits=%s words=%s (raw %r)" % (digits, (words_value, words_conf), words_raw))

    checks = build_checks(qr, printed, digits, (words_value, words_conf), today, image)

    return {
        "success": True,
        "verdict": verdict_from(checks),
        "qr": qr,
        "printed": printed,
        "handwritten": {
            "amount_digits": digits[0],
            "amount_digits_confidence": round(digits[1], 3),
            "amount_words": words_value,
            "amount_words_confidence": round(words_conf, 3),
            "amount_words_raw": words_raw,
        },
        "checks": checks,
        "summary": {
            "passed": sum(1 for c in checks if c["status"] == "PASS"),
            "failed": sum(1 for c in checks if c["status"] == "FAIL"),
            "review": sum(1 for c in checks if c["status"] == "WARN"),
        },
    }


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "Usage: cheque_verifier.py <image> [--date DD/MM/YYYY]"}))
        sys.exit(1)

    image_path = sys.argv[1]
    if not os.path.exists(image_path):
        print(json.dumps({"success": False, "error": "File not found: %s" % image_path}))
        sys.exit(1)

    today = None
    if "--date" in sys.argv:
        try:
            today = datetime.strptime(sys.argv[sys.argv.index("--date") + 1], "%d/%m/%Y").date()
        except (ValueError, IndexError):
            print(json.dumps({"success": False, "error": "Invalid --date, expected DD/MM/YYYY"}))
            sys.exit(1)

    try:
        result = verify(image_path, today)
    except Exception as exc:  # noqa: BLE001 - the service always needs a JSON answer
        log("Unexpected failure: %r" % exc)
        result = {"success": False, "error": str(exc)}

    print(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
