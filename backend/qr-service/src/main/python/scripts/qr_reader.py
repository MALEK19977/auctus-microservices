import cv2
import json
import sys
import re
import os
from pyzbar.pyzbar import decode

def read_qr_code_zbar(image_path):
    """Lit le QR code avec ZBar"""
    try:
        img = cv2.imread(image_path)
        if img is None:
            return {"success": False, "error": "Cannot load image"}
        
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        decoded_objects = decode(gray)
        
        for obj in decoded_objects:
            data = obj.data.decode('utf-8')
            if data and len(data) > 10:
                return parse_qr_data(data)
        
        return {"success": False, "error": "No QR code detected"}
        
    except Exception as e:
        return {"success": False, "error": str(e)}

def parse_qr_data(data):
    try:
        parts = data.split('/')
        
        if len(parts) >= 12:
            return {
                "success": True,
                "raw_data": data,
                "cheque_number": parts[4],
                "rib_titulaire": parts[5],
                "version": parts[6],
                "titulaire": parts[7].replace('%20', ' '),
                "max_amount": parts[8],
                "day": parts[9],
                "month": parts[10],
                "year": parts[11],
                "expiry_date": f"{parts[9]}/{parts[10]}/{parts[11]}",
                "receiver_rib": parts[12] if len(parts) > 12 else ""
            }
        
        return {"success": True, "raw_data": data}
        
    except Exception as e:
        return {"success": True, "raw_data": data}

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"success": False, "error": "Image path required"}))
        sys.exit(1)
    
    image_path = sys.argv[1]
    
    if not os.path.exists(image_path):
        print(json.dumps({"success": False, "error": f"File not found: {image_path}"}))
        sys.exit(1)
    
    result = read_qr_code_zbar(image_path)
    print(json.dumps(result, ensure_ascii=False))