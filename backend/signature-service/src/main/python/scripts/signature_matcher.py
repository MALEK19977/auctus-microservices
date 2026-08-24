"""Signature verification for Zitouna cheques.

Given a cheque image and the name of the account holder (titulaire), extracts the
signature from the cheque, loads that titulaire's reference signature and reports
whether they match.

Usage:
    python signature_matcher.py <cheque_image> <signatures_folder> <titulaire> [cheques_csv]

Only the JSON result goes to stdout; all diagnostics go to stderr.
"""
import csv
import json
import os
import re
import sys

import cv2
import numpy as np

if sys.platform == 'win32':
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

# Geometry of the cheque template used by cheque_generator/generate_cheques.py.
# The signature is pasted at POSITIONS["signature"] with a fixed 160x70 size.
TEMPLATE_W, TEMPLATE_H = 1550, 663
SIG_X, SIG_Y, SIG_W, SIG_H = 1103, 380, 160, 70

# Canonical size both signatures are scaled to before comparison.
CANON = (256, 112)
# Maximum translation (in canonical pixels) tolerated when aligning the two masks.
MAX_SHIFT = 12
# A cheque is accepted when the combined score reaches this value. Measured on the
# generated dataset (see evaluate_matcher.py): genuine signatures score at least
# 0.79, while skilled forgeries peak at 0.68 and other signers at 0.62.
MATCH_THRESHOLD = 0.72

# The master client file is the source of truth for who owns which signature.
DEFAULT_CSV = "C:/Users/21695/OneDrive/Desktop/cheque_generator/output/clients_master.csv"


def log(message):
    print(message, file=sys.stderr)


# ---------------------------------------------------------------------------
# Signature extraction
# ---------------------------------------------------------------------------

def extract_query_signature(image):
    """Crop the signature area from a cheque and return a binary ink mask.

    The signature is drawn in BLEU_MANUSCRIT (48,48,91) over a light blue-grey
    background, so ink is isolated by its blue dominance rather than by a plain
    grey threshold - the background itself is dark enough to fool a grey threshold.
    """
    h, w = image.shape[:2]
    scale_x, scale_y = w / TEMPLATE_W, h / TEMPLATE_H
    x, y = int(SIG_X * scale_x), int(SIG_Y * scale_y)
    box_w, box_h = max(1, int(SIG_W * scale_x)), max(1, int(SIG_H * scale_y))

    if x + box_w > w or y + box_h > h:
        log("Signature box falls outside the image (%dx%d)" % (w, h))
        return None

    crop = image[y:y + box_h, x:x + box_w]
    if crop.size == 0:
        return None

    # Hysteresis on "blueness": seed on pixels that are unmistakably ink, then grow
    # into the softer surrounding pixels. A single hard threshold loses the
    # antialiased stroke edges, and JPEG compression shifts colours enough that a
    # fixed cut-off drops most of the signature.
    blue = crop[:, :, 0].astype(np.int16)
    green = crop[:, :, 1].astype(np.int16)
    red = crop[:, :, 2].astype(np.int16)
    grey = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    blueness = blue - (red + green) // 2

    strong = blueness > 15
    weak = (blueness > -5) & (grey < 175)
    _, labels = cv2.connectedComponents(weak.astype(np.uint8))
    keep = np.unique(labels[strong])
    ink = np.isin(labels, keep[keep != 0]).astype(np.uint8) * 255

    if cv2.countNonZero(ink) < 50:
        log("Signature area is empty (%d ink pixels)" % cv2.countNonZero(ink))
        return None

    # The generator pastes the signature so that it fills the box exactly, so the
    # box is used as-is rather than re-cropping to the ink bounding box.
    return cv2.resize(ink, (SIG_W, SIG_H), interpolation=cv2.INTER_AREA)


def load_reference_signature(path):
    """Load a reference signature and reproduce the generator's cleaning steps.

    Mirrors cheque_generator.clean_signature_image: median filter, threshold the
    ink, crop to the ink bounding box and stretch to the 160x70 paste size.
    """
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        log("Cannot read reference image: %s" % path)
        return None

    img = cv2.medianBlur(img, 3)
    ink = (img < 180).astype(np.uint8) * 255

    ys, xs = np.nonzero(ink)
    if len(xs) == 0:
        log("Reference signature has no ink: %s" % path)
        return None

    ink = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return cv2.resize(ink, (SIG_W, SIG_H), interpolation=cv2.INTER_AREA)


# ---------------------------------------------------------------------------
# Comparison
# ---------------------------------------------------------------------------

def _canonical(mask):
    resized = cv2.resize(mask, CANON, interpolation=cv2.INTER_AREA)
    return (resized > 127).astype(np.uint8) * 255


def _correlation_score(query, reference):
    """Best normalised cross-correlation of the blurred masks over small shifts."""
    q = cv2.GaussianBlur(query.astype(np.float32), (0, 0), 3.0)
    r = cv2.GaussianBlur(reference.astype(np.float32), (0, 0), 3.0)
    padded = cv2.copyMakeBorder(q, MAX_SHIFT, MAX_SHIFT, MAX_SHIFT, MAX_SHIFT,
                                cv2.BORDER_CONSTANT, value=0)
    return float(cv2.matchTemplate(padded, r, cv2.TM_CCOEFF_NORMED).max())


def _overlap_score(query, reference):
    """Best intersection-over-union over small shifts, on slightly thickened strokes."""
    kernel = np.ones((3, 3), np.uint8)
    q = cv2.dilate(query, kernel, iterations=2)
    r = cv2.dilate(reference, kernel, iterations=2) > 127

    best = 0.0
    for dy in range(-MAX_SHIFT, MAX_SHIFT + 1, 2):
        for dx in range(-MAX_SHIFT, MAX_SHIFT + 1, 2):
            matrix = np.float32([[1, 0, dx], [0, 1, dy]])
            shifted = cv2.warpAffine(q, matrix, (q.shape[1], q.shape[0])) > 127
            union = np.logical_or(shifted, r).sum()
            if union:
                best = max(best, np.logical_and(shifted, r).sum() / union)
    return float(best)


def _stroke_direction_score(query, reference):
    """Cosine similarity of block-wise gradient orientation histograms.

    Captures the direction the pen travelled in each part of the signature, which
    a forger reproduces less faithfully than the overall outline.
    """
    return float(np.dot(_direction_histogram(query), _direction_histogram(reference)))


def _direction_histogram(mask):
    blurred = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), 2.0)
    gx = cv2.Sobel(blurred, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(blurred, cv2.CV_32F, 0, 1, ksize=3)
    magnitude = np.sqrt(gx * gx + gy * gy)
    angle = (np.arctan2(gy, gx) % np.pi) / np.pi  # direction-insensitive, 0..1

    rows, cols, bins = 4, 8, 9
    cell_h, cell_w = mask.shape[0] // rows, mask.shape[1] // cols
    cells = []
    for r in range(rows):
        for c in range(cols):
            window = (slice(r * cell_h, (r + 1) * cell_h), slice(c * cell_w, (c + 1) * cell_w))
            hist, _ = np.histogram(angle[window], bins=bins, range=(0, 1),
                                   weights=magnitude[window])
            cells.append(hist)

    feature = np.concatenate(cells)
    norm = np.linalg.norm(feature)
    return feature / norm if norm else feature


def _local_overlap_score(query, reference):
    """Mean per-block IoU - a forgery matches globally but drifts locally."""
    kernel = np.ones((3, 3), np.uint8)
    q = cv2.dilate(query, kernel, iterations=2) > 127
    r = cv2.dilate(reference, kernel, iterations=2) > 127

    rows, cols = 3, 6
    cell_h, cell_w = q.shape[0] // rows, q.shape[1] // cols
    scores = []
    for i in range(rows):
        for j in range(cols):
            window = (slice(i * cell_h, (i + 1) * cell_h), slice(j * cell_w, (j + 1) * cell_w))
            a, b = q[window], r[window]
            union = np.logical_or(a, b).sum()
            if union > 20:  # ignore blocks that are blank in both signatures
                scores.append(np.logical_and(a, b).sum() / union)
    return float(np.mean(scores)) if scores else 0.0


def compare(query_mask, reference_mask):
    """Return a similarity score in [0,1] for two signature masks.

    Three complementary views are blended: how well the shapes correlate once
    aligned, which direction the strokes run, and how well they overlap block by
    block. No single one of them separates skilled forgeries on its own.
    """
    query = _canonical(query_mask)
    reference = _canonical(reference_mask)

    shape = 0.5 * _correlation_score(query, reference) + 0.5 * _overlap_score(query, reference)
    direction = _stroke_direction_score(query, reference)
    local = _local_overlap_score(query, reference)
    score = 0.4 * shape + 0.3 * direction + 0.3 * local

    log("shape=%.3f direction=%.3f local=%.3f -> score=%.3f" % (shape, direction, local, score))
    return score


# ---------------------------------------------------------------------------
# Reference lookup
# ---------------------------------------------------------------------------

def _squash(value):
    """Upper-case and strip every space, so 'SALMA BOUAZIZI' == 'SALMABOUAZIZI'.

    The QR payload has its spaces removed before encoding, so the account holder
    name arrives glued together and can never match the spaced name on file.
    """
    return re.sub(r"\s+", "", (value or "")).upper()


def find_reference_signature(identifier, signatures_folder, register_path):
    """Resolve the enrolled signature of the account holder.

    `identifier` may be a RIB or a name. A RIB is preferred: it is unique, whereas
    two clients can share a name. Returns None when the holder is unknown - it
    never falls back to an arbitrary signature, which would make the whole check
    meaningless.
    """
    wanted = _squash(identifier)
    log("Looking up enrolled signature for '%s'" % identifier)

    if not os.path.exists(register_path):
        log("Client register not found: %s" % register_path)
        return None

    with open(register_path, 'r', encoding='utf-8-sig') as handle:
        rows = list(csv.DictReader(handle))

    # The master client file is the source of truth; older cheque registers use
    # different column names, so both layouts are accepted.
    def matches(row):
        for key in ("rib", "titulaire_rib", "account_number"):
            if row.get(key) and _squash(row[key]) == wanted:
                return True
        for key in ("full_name", "titulaire_nom"):
            if row.get(key) and _squash(row[key]) == wanted:
                return True
        return False

    for row in rows:
        if not matches(row):
            continue
        dossier = (row.get("signature_dossier") or "").strip()
        image = (row.get("signature_image") or "").strip()
        if not dossier or not image:
            continue
        path = os.path.join(signatures_folder, dossier, image)
        if os.path.exists(path):
            log("Enrolled signature: %s" % path)
            return path
        log("Enrolled signature file is missing: %s" % path)

    log("No signature enrolled for '%s'" % identifier)
    return None


# ---------------------------------------------------------------------------

def verify(image_path, signatures_folder, titulaire, csv_path):
    reference_path = find_reference_signature(titulaire, signatures_folder, csv_path)
    if reference_path is None:
        return {"success": False, "error": "No reference signature registered for: %s" % titulaire}

    cheque = cv2.imread(image_path)
    if cheque is None:
        return {"success": False, "error": "Cannot load cheque image"}

    query_mask = extract_query_signature(cheque)
    if query_mask is None:
        return {"success": False, "error": "No signature found on the cheque"}

    reference_mask = load_reference_signature(reference_path)
    if reference_mask is None:
        return {"success": False, "error": "Cannot load reference signature"}

    score = compare(query_mask, reference_mask)
    return {
        "success": True,
        "is_valid": bool(score >= MATCH_THRESHOLD),
        "score": round(float(score), 4),
        "threshold": MATCH_THRESHOLD,
        "titulaire": titulaire,
        "reference": os.path.basename(reference_path),
    }


def main():
    if len(sys.argv) < 4:
        print(json.dumps({
            "success": False,
            "error": "Usage: signature_matcher.py <cheque_image> <signatures_folder> <titulaire> [cheques_csv]"
        }))
        sys.exit(1)

    image_path = sys.argv[1]
    signatures_folder = sys.argv[2]
    titulaire = sys.argv[3]
    csv_path = sys.argv[4] if len(sys.argv) > 4 else DEFAULT_CSV

    if not os.path.exists(image_path):
        print(json.dumps({"success": False, "error": "File not found: %s" % image_path}))
        sys.exit(1)

    if not os.path.isdir(signatures_folder):
        print(json.dumps({"success": False, "error": "Signatures folder not found: %s" % signatures_folder}))
        sys.exit(1)

    try:
        result = verify(image_path, signatures_folder, titulaire, csv_path)
    except Exception as exc:  # noqa: BLE001 - the service needs a JSON answer either way
        log("Unexpected failure: %r" % exc)
        result = {"success": False, "error": str(exc)}

    print(json.dumps(result, ensure_ascii=False))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
