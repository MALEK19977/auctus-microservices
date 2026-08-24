import cv2
import numpy as np
import pytesseract
import re
import json
import sys
import os
import io

# UTF-8 pour Windows
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

# Configuration Tesseract
pytesseract.pytesseract.tesseract_cmd = r'C:\Program Files\Tesseract-OCR\tesseract.exe'

# ============================================
# CONFIGURATION
# ============================================

MIN_CONFIDENCE = 30
MAX_IMAGE_SIZE = 10 * 1024 * 1024
SUPPORTED_FORMATS = ['.jpg', '.jpeg', '.png', '.bmp', '.tiff']

# Mots-cles elargis
REQUIRED_KEYWORDS = [
    'BANQUE', 'ZITOUNA', 'CHEQUE', 'CHÈQUE', 'DT', 'DATE',
    'VALEUR', 'MAXIMALE', 'EXPIRATION', 'SIGNATURE', 'TITULAIRE'
]

# Patterns de detection
PATTERNS = {
    'cheque_number': [
        r'CH[EÈ]QUE\s*N[°°]\s*(\d{7,10})',
        r'N[°°]\s*(\d{7,10})',
        r'(\d{7})(?=\s|$|\.|\n)'
    ],
    'amount': [
        r'VALEUR\s*MAXIMALE\s*DT\s*(\d{3,5})',
        r'DT\s*(\d{3,5})',
        r'(\d{3,5})\s*DT'
    ],
    'date': [
        r'(\d{2}[/-]\d{2}[/-]\d{4})',
        r'DATE\s*D\'EXPIRATION\s*[:\-]?\s*(\d{2}[/-]\d{2}[/-]\d{4})',
        r'(\d{2}/\d{2}/\d{4})'
    ],
    'rib': [
        r'21\s*(\d{3})\s*(\d{13})\s*(\d{2})',
        r'(\d{2}\s*\d{3}\s*\d{13}\s*\d{2})'
    ]
}

# ============================================
# FONCTIONS
# ============================================

def enhance_image_for_ocr(gray_image):
    """Ameliore l'image pour l'OCR"""
    enhanced = cv2.equalizeHist(gray_image)
    enhanced = cv2.medianBlur(enhanced, 3)
    kernel = np.array([[-1,-1,-1], [-1,9,-1], [-1,-1,-1]])
    enhanced = cv2.filter2D(enhanced, -1, kernel)
    return enhanced

def detect_cheque_layout(image):
    """Detection des caracteristiques physiques d'un cheque"""
    height, width = image.shape[:2]
    aspect_ratio = width / height if height > 0 else 0
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    
    if contours:
        max_area = max(cv2.contourArea(c) for c in contours)
        image_area = width * height
        cheque_area_ratio = max_area / image_area if image_area > 0 else 0
    else:
        cheque_area_ratio = 0
    
    return {
        'aspect_ratio': aspect_ratio,
        'cheque_area_ratio': cheque_area_ratio,
        'is_rectangular': 1.5 <= aspect_ratio <= 2.8,
        'has_borders': cheque_area_ratio > 0.6
    }

# ============================================
# CLASSE DE VALIDATION
# ============================================

class ChequeImageValidator:
    
    def __init__(self, image_path):
        self.image_path = image_path
        self.image = None
        self.original = None
        self.text = ""
        self.detected_fields = {}
        self.warnings = []
        self.image_stats = {
            'width': 0, 'height': 0, 'aspect_ratio': 0,
            'file_size': 0, 'format': '', 'cheque_area_ratio': 0
        }
        self.result = {
            'is_cheque': False,
            'confidence': 0,
            'detected_fields': {},
            'detected_keywords': [],
            'extracted_text': '',
            'warnings': [],
            'image_stats': {},
            'validation_details': {}
        }
    
    def validate_file(self):
        try:
            self.image_stats['file_size'] = os.path.getsize(self.image_path)
            if self.image_stats['file_size'] > MAX_IMAGE_SIZE:
                self.warnings.append("File too large")
                return False
            
            ext = os.path.splitext(self.image_path)[1].lower()
            if ext not in SUPPORTED_FORMATS:
                self.warnings.append(f"Unsupported format: {ext}")
                return False
            
            self.image_stats['format'] = ext
            return True
        except Exception as e:
            self.warnings.append(str(e))
            return False
    
    def load_and_preprocess(self):
        try:
            self.original = cv2.imread(self.image_path)
            if self.original is None:
                self.warnings.append("Cannot load image")
                return False
            
            height, width = self.original.shape[:2]
            self.image_stats['width'] = width
            self.image_stats['height'] = height
            self.image_stats['aspect_ratio'] = width / height if height > 0 else 0
            
            if width > 2000:
                scale = 2000 / width
                new_width = int(width * scale)
                new_height = int(height * scale)
                self.image = cv2.resize(self.original, (new_width, new_height))
            else:
                self.image = self.original.copy()
            
            return True
        except Exception as e:
            self.warnings.append(str(e))
            return False
    
    def extract_text_enhanced(self):
        try:
            gray = cv2.cvtColor(self.image, cv2.COLOR_BGR2GRAY)
            
            versions = [
                gray,
                cv2.equalizeHist(gray),
                cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 11, 2),
                enhance_image_for_ocr(gray)
            ]
            
            configs = ['--psm 6 --oem 3', '--psm 4 --oem 3', '--psm 3 --oem 3']
            langs = ['fra', 'eng', 'fra+eng']
            
            all_texts = []
            
            for img in versions:
                for lang in langs:
                    for config in configs:
                        try:
                            text = pytesseract.image_to_string(img, lang=lang, config=config)
                            if len(text.strip()) > 20:
                                all_texts.append(text)
                        except:
                            continue
            
            full_text = ' '.join(all_texts).upper()
            full_text = re.sub(r'[^\w\s\d\-/]', ' ', full_text)
            full_text = re.sub(r'\s+', ' ', full_text)
            
            self.text = full_text.strip()
            self.result['extracted_text'] = self.text[:500]
            
            return True
        except Exception as e:
            self.warnings.append(str(e))
            return False
    
    def detect_fields_enhanced(self):
        self.detected_fields = {}
        
        for field_name, patterns in PATTERNS.items():
            for pattern in patterns:
                match = re.search(pattern, self.text, re.IGNORECASE)
                if match:
                    value = match.group(1) if match.groups() else match.group(0)
                    value = value.strip()
                    if value:
                        self.detected_fields[field_name] = value
                        break
        
        if 'cheque_number' in self.detected_fields:
            num = re.sub(r'\D', '', self.detected_fields['cheque_number'])
            if len(num) >= 7:
                self.detected_fields['cheque_number'] = num[:7]
        
        self.result['detected_fields'] = self.detected_fields
        return self.detected_fields
    
    def check_keywords_enhanced(self):
        found = []
        score = 0
        
        primary_keywords = ['BANQUE', 'ZITOUNA', 'CHEQUE']
        for kw in primary_keywords:
            if kw in self.text:
                found.append(kw)
                score += 15
        
        secondary_keywords = ['DT', 'DATE', 'VALEUR', 'MAXIMALE', 'EXPIRATION']
        for kw in secondary_keywords:
            if kw in self.text:
                found.append(kw)
                score += 5
        
        return found, score
    
    def check_layout_enhanced(self):
        layout = detect_cheque_layout(self.original)
        score = 0
        
        if layout['is_rectangular']:
            score += 10
        
        if layout['has_borders']:
            score += 10
        
        return score
    
    def calculate_confidence_enhanced(self):
        found_keywords, keyword_score = self.check_keywords_enhanced()
        keyword_score = min(40, keyword_score)
        field_score = (len(self.detected_fields) / len(PATTERNS)) * 40
        layout_score = self.check_layout_enhanced()
        total_score = keyword_score + field_score + layout_score
        
        self.result['validation_details'] = {
            'keyword_score': round(keyword_score, 2),
            'field_score': round(field_score, 2),
            'layout_score': layout_score,
            'total_score': round(total_score, 2)
        }
        
        self.result['detected_keywords'] = found_keywords
        return total_score
    
    def validate(self):
        if not self.validate_file():
            self.result['is_cheque'] = False
            return self.result
        
        if not self.load_and_preprocess():
            self.result['is_cheque'] = False
            return self.result
        
        if not self.extract_text_enhanced():
            self.result['is_cheque'] = False
            return self.result
        
        self.detect_fields_enhanced()
        confidence = self.calculate_confidence_enhanced()
        
        self.result['confidence'] = round(confidence, 2)
        self.result['is_cheque'] = confidence >= MIN_CONFIDENCE
        self.result['warnings'] = self.warnings
        self.result['image_stats'] = self.image_stats
        
        return self.result

def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Image path required"}, ensure_ascii=False))
        sys.exit(1)
    
    image_path = sys.argv[1]
    
    if not os.path.exists(image_path):
        print(json.dumps({"error": f"File not found: {image_path}"}, ensure_ascii=False))
        sys.exit(1)
    
    validator = ChequeImageValidator(image_path)
    result = validator.validate()
    
    output = {
        "is_cheque": result['is_cheque'],
        "confidence": result['confidence'],
        "detected_keywords": result['detected_keywords'],
        "detected_fields": result['detected_fields'],
        "aspect_ratio": result['image_stats']['aspect_ratio'],
        "dimensions": f"{result['image_stats']['width']}x{result['image_stats']['height']}",
        "validation_details": result['validation_details']
    }
    
    print(json.dumps(output, ensure_ascii=False))

if __name__ == "__main__":
    main()