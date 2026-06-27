#!/usr/bin/env python3
"""Download ImageStream WebP decoder source files from GitHub into YSMU source tree.

Usage: python tools/vendor_imagestream.py
"""

import os
import urllib.request
import urllib.error

BASE_URL = "https://raw.githubusercontent.com/OpenYSM/ImageStream/master/src/main/java"
TARGET_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src", "main", "java"))

# Files needed for WebP decoding only (no encoder, no JPEG, no AVIF)
FILES = [
    # Utility classes
    "rip/ysm/imagestream/utility/DataReader.java",
    "rip/ysm/imagestream/utility/DataByteReader.java",
    "rip/ysm/imagestream/utility/DataByteLittle.java",
    "rip/ysm/imagestream/utility/DataFileReader.java",
    "rip/ysm/imagestream/utility/DataFileLittle.java",
    # WebP decoder entry point
    "rip/ysm/imagestream/webp/WebpDecoder.java",
    # WebP data classes
    "rip/ysm/imagestream/webp/data/Frame.java",
    "rip/ysm/imagestream/webp/data/WDecoder.java",
    "rip/ysm/imagestream/webp/data/MacroBlock.java",
    "rip/ysm/imagestream/webp/data/SubBlock.java",
    "rip/ysm/imagestream/webp/data/LookUp.java",
    "rip/ysm/imagestream/webp/data/Predictor.java",
    "rip/ysm/imagestream/webp/data/Transform.java",
    "rip/ysm/imagestream/webp/data/BitDecoder.java",
    "rip/ysm/imagestream/webp/data/WBit.java",
    "rip/ysm/imagestream/webp/data/Vp8LBit.java",
    "rip/ysm/imagestream/webp/data/SegmentQuants.java",
    "rip/ysm/imagestream/webp/data/SegmentQ.java",
    "rip/ysm/imagestream/webp/data/ColorIndexing.java",
    "rip/ysm/imagestream/webp/data/ColorMap.java",
    "rip/ysm/imagestream/webp/data/ColorTransform.java",
    "rip/ysm/imagestream/webp/data/HuffmanGroup.java",
    "rip/ysm/imagestream/webp/data/HuffmanInfo.java",
    "rip/ysm/imagestream/webp/data/HuffmanTable.java",
    "rip/ysm/imagestream/webp/data/LBuffer.java",
    "rip/ysm/imagestream/webp/data/Picture.java",
    "rip/ysm/imagestream/webp/data/SubtractGreen.java",
    "rip/ysm/imagestream/webp/data/Util.java",
    "rip/ysm/imagestream/webp/data/WebpYUV.java",
    "rip/ysm/imagestream/webp/data/WTransform.java",
    "rip/ysm/imagestream/webp/data/EBool.java",
    "rip/ysm/imagestream/webp/data/EQuantizer.java",
]

def download_file(url, target_path):
    """Download a single file, creating directories as needed."""
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    try:
        print(f"Downloading: {url}")
        with urllib.request.urlopen(url) as response:
            content = response.read().decode("utf-8")
            with open(target_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
            print(f"  -> {target_path} ({len(content)} bytes)")
            return True
    except urllib.error.HTTPError as e:
        print(f"  ERROR {e.code}: {url}")
        return False
    except Exception as e:
        print(f"  ERROR: {e}")
        return False

def main():
    success = 0
    failed = 0
    for rel_path in FILES:
        url = f"{BASE_URL}/{rel_path}"
        target = os.path.join(TARGET_DIR, rel_path)
        if download_file(url, target):
            success += 1
        else:
            failed += 1
    
    print(f"\nDone: {success} downloaded, {failed} failed")

if __name__ == "__main__":
    main()
