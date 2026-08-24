"""Evaluate signature_matcher against the generated cheque dataset.

Reports score distributions for four populations:
  genuine   - the cheque is signed with the titulaire's registered signature
  variant   - same signer, a different sample of their signature
  forgery   - a forged version of the titulaire's signature
  impostor  - a different signer's signature

Usage: python evaluate_matcher.py [n_cheques]
"""
import csv
import os
import random
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import signature_matcher as sm

IMAGES_DIR = "C:/Users/21695/OneDrive/Desktop/cheque_generator/output/images"
SIG_DIR = "C:/Users/21695/OneDrive/Desktop/cheque_generator/signatures/extract"
CHEQUES_CSV = sm.DEFAULT_CSV


def other_sample(dossier, exclude, suffix=""):
    folder = os.path.join(SIG_DIR, dossier + suffix)
    if not os.path.isdir(folder):
        return None
    names = sorted(f for f in os.listdir(folder)
                   if f.lower().endswith(('.jpg', '.jpeg', '.png')) and f != exclude)
    return os.path.join(folder, names[0]) if names else None


def describe(name, scores):
    if not scores:
        print("%-9s no samples" % name)
        return
    a = np.array(scores)
    print("%-9s n=%-4d min=%.3f  mean=%.3f  max=%.3f  >=thr: %d (%.0f%%)"
          % (name, len(a), a.min(), a.mean(), a.max(),
             int((a >= sm.MATCH_THRESHOLD).sum()), 100.0 * (a >= sm.MATCH_THRESHOLD).mean()))


def main():
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    rows = list(csv.DictReader(open(CHEQUES_CSV, encoding='utf-8-sig')))[:limit]
    random.seed(0)

    populations = {"genuine": [], "variant": [], "forgery": [], "impostor": []}
    all_refs = [(r["signature_dossier"], r["signature_image"]) for r in rows]
    skipped = 0

    for index, row in enumerate(rows):
        dossier = row["signature_dossier"]
        image_name = row["signature_image"]
        cheque_path = os.path.join(IMAGES_DIR, "zitouna_%04d_%s.png" % (index + 1, dossier))
        if not os.path.exists(cheque_path):
            skipped += 1
            continue

        cheque = cv2.imread(cheque_path)
        query = sm.extract_query_signature(cheque)
        if query is None:
            skipped += 1
            continue

        registered = sm.load_reference_signature(os.path.join(SIG_DIR, dossier, image_name))
        if registered is not None:
            populations["genuine"].append(sm.compare(query, registered))

        variant_path = other_sample(dossier, image_name)
        if variant_path:
            mask = sm.load_reference_signature(variant_path)
            if mask is not None:
                populations["variant"].append(sm.compare(query, mask))

        forgery_path = other_sample(dossier, "", suffix="_forg")
        if forgery_path:
            mask = sm.load_reference_signature(forgery_path)
            if mask is not None:
                populations["forgery"].append(sm.compare(query, mask))

        for other_dossier, other_image in random.sample(
                [p for p in all_refs if p[0] != dossier], min(8, len(all_refs) - 1)):
            mask = sm.load_reference_signature(os.path.join(SIG_DIR, other_dossier, other_image))
            if mask is not None:
                populations["impostor"].append(sm.compare(query, mask))

    print("\n" + "=" * 66)
    print("threshold = %.2f   (cheques evaluated: %d, skipped: %d)"
          % (sm.MATCH_THRESHOLD, len(populations["genuine"]), skipped))
    print("=" * 66)
    for name in ("genuine", "variant", "forgery", "impostor"):
        describe(name, populations[name])

    genuine = np.array(populations["genuine"])
    rejected = np.concatenate([np.array(populations[k]) for k in ("forgery", "impostor")
                               if populations[k]])
    if genuine.size and rejected.size:
        print("\nseparation gap: genuine min %.3f vs should-reject max %.3f -> %s"
              % (genuine.min(), rejected.max(),
                 "OK" if genuine.min() > rejected.max() else "OVERLAP"))


if __name__ == "__main__":
    main()
