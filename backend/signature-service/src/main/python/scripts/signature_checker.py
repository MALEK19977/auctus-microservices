import cv2
import numpy as np
import json
import sys
import os
import io

if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

def extract_signature_region(image):
    """Extrait la zone de signature - Position exacte pour les chèques générés"""
    try:
        height, width = image.shape[:2]
        
        # Position exacte de la signature dans les chèques générés
        # D'après generate_cheques.py: "signature": (1103, 380)
        x, y, w, h = 1103, 380, 160, 70
        
        if x + w <= width and y + h <= height:
            signature = image[y:y+h, x:x+w]
            if signature is not None and signature.size > 0:
                return signature
        
        # Fallback: recherche par contours
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        edges = cv2.Canny(gray, 50, 150)
        contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        
        best_region = None
        best_area = 0
        
        for contour in contours:
            cx, cy, cw, ch = cv2.boundingRect(contour)
            area = cw * ch
            if cx > width * 0.6 and cy > height * 0.6 and 1000 < area < 20000:
                if area > best_area:
                    best_area = area
                    best_region = (cx, cy, cw, ch)
        
        if best_region:
            cx, cy, cw, ch = best_region
            return image[cy:cy+ch, cx:cx+cw]
        
        return None
        
    except Exception as e:
        return None

def preprocess_signature(signature_img):
    """Prétraitement de la signature"""
    try:
        if signature_img is None:
            return None
        
        if len(signature_img.shape) == 3:
            gray = cv2.cvtColor(signature_img, cv2.COLOR_BGR2GRAY)
        else:
            gray = signature_img
        
        # Réduction du bruit
        denoised = cv2.medianBlur(gray, 3)
        
        # Amélioration du contraste
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8,8))
        enhanced = clahe.apply(denoised)
        
        # Binarisation
        binary = cv2.adaptiveThreshold(enhanced, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2)
        
        # Nettoyage morphologique
        kernel = np.ones((2,2), np.uint8)
        cleaned = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)
        cleaned = cv2.morphologyEx(cleaned, cv2.MORPH_OPEN, kernel)
        
        return cleaned
        
    except Exception as e:
        return None

def calculate_similarity(img1, img2):
    """Calcule la similarité entre deux signatures"""
    try:
        # Redimensionner à taille standard
        img1_resized = cv2.resize(img1, (200, 80))
        img2_resized = cv2.resize(img2, (200, 80))
        
        # Corrélation croisée
        result = cv2.matchTemplate(img1_resized, img2_resized, cv2.TM_CCOEFF_NORMED)
        correlation = float(np.max(result))
        
        # Différence de moyenne
        mean1 = np.mean(img1_resized)
        mean2 = np.mean(img2_resized)
        mean_score = 1.0 - (abs(mean1 - mean2) / 255.0)
        
        # Histogrammes
        hist1 = cv2.calcHist([img1_resized], [0], None, [256], [0, 256])
        hist2 = cv2.calcHist([img2_resized], [0], None, [256], [0, 256])
        hist_score = float(cv2.compareHist(hist1, hist2, cv2.HISTCMP_CORREL))
        
        # Score final (pondération)
        score = (correlation * 0.5) + (mean_score * 0.2) + (hist_score * 0.3)
        
        return float(score)
        
    except Exception as e:
        return 0.0

def get_reference_signature_path(titulaire, signatures_folder):
    """Trouve le chemin de la signature de référence pour un titulaire"""
    try:
        # Mapping des titulaires vers leurs dossiers de signature
        mapping = {
            "EXCELLIA": "001/1-001_01.jpg",
            "EXCELLIA SOLUTIONS": "001/1-001_01.jpg",
            "SOTUVER": "002/1-003_01.jpg",
            "TUNISAIR": "003/1-005_01.jpg",
            "POULINA": "004/2-002_01.jpg",
            "POULINA GROUP": "004/2-002_01.jpg",
            "DELICE": "005/2-004_01.jpg",
            "DELICE HOLDING": "005/2-004_01.jpg",
            "ARTES": "018/7-005_01.jpg",
            "CARTHAGE": "022/9-003_01.jpg",
            "HANNIBAL": "023/9-005_01.jpg",
            "DIDON": "024/10-002_01.jpg",
            "ELJEM": "025/10-004_01.jpg",
            "KAIROUAN": "026/11-001_01.jpg",
            "SFAX": "027/11-003_01.jpg",
            "SOUSSE": "028/11-005_01.jpg",
            "NABEUL": "029/12-002_01.jpg",
            "BIZERTE": "030/12-004_01.jpg",
            "MONASTIR": "031/13-001_01.jpg",
            "MAHDIA": "032/13-003_01.jpg",
            "GABES": "033/13-005_01.jpg",
            "TATAOUINE": "034/14-002_01.jpg",
            "BEJA": "035/14-004_01.jpg",
            "JENDOUBA": "036/15-001_01.jpg"
        }
        
        titulaire_upper = titulaire.upper().strip()
        
        # Recherche exacte puis partielle
        for key, path in mapping.items():
            if key == titulaire_upper or key in titulaire_upper or titulaire_upper in key:
                full_path = os.path.join(signatures_folder, path)
                if os.path.exists(full_path):
                    return full_path
        
        return None
        
    except Exception as e:
        return None

def verify_signature(image_path, signatures_folder, titulaire):
    """Vérification complète de la signature"""
    try:
        img = cv2.imread(image_path)
        if img is None:
            return {"success": False, "error": "Cannot load image"}
        
        # Extraire la signature
        signature_region = extract_signature_region(img)
        if signature_region is None:
            return {"success": False, "error": "Cannot extract signature region"}
        
        # Trouver la signature de référence
        reference_path = get_reference_signature_path(titulaire, signatures_folder)
        
        if reference_path is None:
            return {"success": False, "error": f"No reference signature found for titulaire: {titulaire}"}
        
        # Prétraiter les signatures
        processed_sig = preprocess_signature(signature_region)
        processed_ref = preprocess_signature(cv2.imread(reference_path))
        
        if processed_sig is None or processed_ref is None:
            return {"success": False, "error": "Cannot process signatures"}
        
        # Calculer la similarité
        score = calculate_similarity(processed_sig, processed_ref)
        
        return {
            "success": True,
            "is_valid": score >= 0.5,
            "score": float(score)
        }
        
    except Exception as e:
        return {"success": False, "error": str(e)}

def main():
    if len(sys.argv) < 4:
        print(json.dumps({"success": False, "error": "Usage: python signature_checker.py <image_path> <signatures_folder> <titulaire>"}))
        sys.exit(1)
    
    image_path = sys.argv[1]
    signatures_folder = sys.argv[2]
    titulaire = sys.argv[3]
    
    if not os.path.exists(image_path):
        print(json.dumps({"success": False, "error": f"File not found: {image_path}"}))
        sys.exit(1)
    
    if not os.path.exists(signatures_folder):
        print(json.dumps({"success": False, "error": f"Signatures folder not found: {signatures_folder}"}))
        sys.exit(1)
    
    result = verify_signature(image_path, signatures_folder, titulaire)
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()