import os
from importlib.metadata import PackageNotFoundError, distribution, version
from importlib.util import find_spec
from pathlib import Path
from typing import Dict, List, Optional

os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")

try:
    from PIL import Image, ImageEnhance, ImageOps
except ImportError:  # Pillow is optional unless OCR checks are used
    Image = None  # type: ignore[assignment]

try:
    from paddleocr import PaddleOCR
except ImportError:  # PaddleOCR is optional unless Chinese OCR checks are used
    PaddleOCR = None  # type: ignore[assignment]

SCRIPT_DIR = Path(__file__).resolve().parent
TMP_DIR = SCRIPT_DIR.parent / "tmp"
OCR_CROP_PATH = TMP_DIR / "adb_ocr_crop.png"

OCR_LANGUAGE_ALIASES: Dict[str, str] = {
    "en": "en",
    "eng": "en",
    "english": "en",
    "zh": "chi_sim",
    "zh-cn": "chi_sim",
    "zh-hans": "chi_sim",
    "chinese": "chi_sim",
    "chi_sim": "chi_sim",
    "zh-tw": "chi_tra",
    "zh-hant": "chi_tra",
    "chi_tra": "chi_tra",
}
DEFAULT_OCR_LANG = "eng"
_PADDLE_OCR_CACHE: Dict[str, "PaddleOCR"] = {}


def _validate_paddle_runtime() -> None:
    # `paddleocr` requires the `paddlepaddle` runtime package.
    # Some environments accidentally install another package named `paddle`.
    try:
        paddle_dist = distribution("paddle")
        paddle_name = (paddle_dist.metadata.get("Name") or "").lower()
    except PackageNotFoundError:
        paddle_name = ""

    try:
        paddlepaddle_version = version("paddlepaddle")
    except PackageNotFoundError:
        paddlepaddle_version = ""

    if paddle_name and not paddlepaddle_version:
        raise RuntimeError(
            "Detected package `paddle` but `paddlepaddle` is missing. "
            "This is usually the wrong package for PaddleOCR. "
            "Fix with: `pip uninstall -y paddle` then "
            "`pip install paddlepaddle` (or macOS-specific variant)."
        )

    if find_spec("paddle") is None:
        raise RuntimeError(
            "Paddle runtime is not installed. Install `paddlepaddle` "
            "(or macOS variant) in the current Python environment."
        )


def normalize_ocr_lang(value: str) -> str:
    parts = [part.strip() for part in value.split("+") if part.strip()]
    if not parts:
        return value.strip()
    normalized_parts = [OCR_LANGUAGE_ALIASES.get(part.lower(), part) for part in parts]
    return "+".join(normalized_parts)


def _lang_parts(ocr_lang: str) -> List[str]:
    return [part.strip() for part in normalize_ocr_lang(ocr_lang).split("+") if part.strip()]


def _paddle_lang_for(ocr_lang: str) -> str:
    parts = set(_lang_parts(ocr_lang))
    if "chi_sim" in parts or "chi_tra" in parts:
        return "ch"
    return "en"


def _get_paddle_ocr(paddle_lang: str) -> "PaddleOCR":
    if PaddleOCR is None:
        raise RuntimeError(
            "PaddleOCR is required for Chinese OCR. Install with "
            "`pip install paddleocr` and a Paddle runtime "
            "(`pip install paddlepaddle` or macOS variant)."
        )
    _validate_paddle_runtime()
    cached = _PADDLE_OCR_CACHE.get(paddle_lang)
    if cached is not None:
        return cached

    try:
        engine = PaddleOCR(use_textline_orientation=True, lang=paddle_lang)
    except Exception as exc:
        raise RuntimeError(
            "Failed to initialize PaddleOCR runtime. "
            "Check that `paddlepaddle` is installed correctly in this venv, "
            "and remove conflicting `paddle` package if present."
        ) from exc
    _PADDLE_OCR_CACHE[paddle_lang] = engine
    return engine


def _extract_text_from_paddle_result(result: object) -> str:
    texts: List[str] = []

    def collect(node: object) -> None:
        if isinstance(node, str):
            stripped = node.strip()
            if stripped:
                texts.append(stripped)
            return

        if isinstance(node, tuple):
            for item in node:
                collect(item)
            return

        if isinstance(node, list):
            for item in node:
                collect(item)
            return

        if isinstance(node, dict):
            # Newer PaddleOCR may return dict structures with explicit text keys.
            for key in ("rec_text", "text"):
                value = node.get(key)
                if isinstance(value, str):
                    collect(value)
            for key in ("rec_texts", "texts"):
                value = node.get(key)
                if isinstance(value, list):
                    collect(value)
            # Legacy style nested under "res" or "result".
            nested = node.get("res")
            if nested is not None:
                collect(nested)
            nested = node.get("result")
            if nested is not None:
                collect(nested)
            # Old-style line format: {"text": "...", ...} already covered above.
            return

    collect(result)

    # Keep order while removing duplicates.
    unique: List[str] = []
    seen = set()
    for text in texts:
        if text in seen:
            continue
        seen.add(text)
        unique.append(text)
    return "\n".join(unique)


def _ocr_with_paddle(crop_path: Path, ocr_lang: str) -> str:
    paddle_lang = _paddle_lang_for(ocr_lang)
    engine = _get_paddle_ocr(paddle_lang)
    if hasattr(engine, "predict"):
        result = engine.predict(str(crop_path))
    else:
        result = engine.ocr(str(crop_path))
    return _extract_text_from_paddle_result(result)


def preload_ocr_models(ocr_langs: List[str]) -> None:
    normalized = [normalize_ocr_lang(lang) for lang in ocr_langs if lang.strip()]
    if not normalized:
        normalized = [DEFAULT_OCR_LANG]

    paddle_langs = {_paddle_lang_for(lang) for lang in normalized}
    for paddle_lang in sorted(paddle_langs):
        _get_paddle_ocr(paddle_lang)


def _build_ocr_variants(region: "Image.Image") -> List["Image.Image"]:
    variants: List["Image.Image"] = []
    base = region.convert("RGB")
    variants.append(base)

    upscaled = base.resize((base.width * 2, base.height * 2), Image.Resampling.LANCZOS)
    variants.append(upscaled)

    gray = ImageOps.grayscale(upscaled)
    gray = ImageOps.autocontrast(gray)
    variants.append(gray.convert("RGB"))

    high_contrast = ImageEnhance.Contrast(gray).enhance(1.8)
    variants.append(high_contrast.convert("RGB"))

    binary = high_contrast.point(lambda p: 255 if p > 150 else 0)
    variants.append(binary.convert("RGB"))
    return variants

def read_text_in_region(
    image_path: Path,
    x1: int,
    y1: int,
    x2: int,
    y2: int,
    ocr_lang: str,
    crop_output_path: Optional[Path] = OCR_CROP_PATH,
) -> str:
    if Image is None:
        raise RuntimeError(
            "Pillow is required when using CHECK_OCR. Install with `pip install pillow`."
        )

    normalized_lang = normalize_ocr_lang(ocr_lang)
    if not _lang_parts(normalized_lang):
        raise RuntimeError("OCR language is empty")

    with Image.open(image_path) as img:
        width, height = img.size
        left = min(x1, x2)
        right = max(x1, x2)
        top = min(y1, y2)
        bottom = max(y1, y2)
        if left < 0 or top < 0 or right > width or bottom > height:
            raise ValueError(
                f"region ({x1}, {y1}, {x2}, {y2}) is outside screenshot bounds {width}x{height}"
            )
        if left == right or top == bottom:
            raise ValueError("region width and height must be greater than 0")

        # Add padding so OCR detector has context around text boundaries.
        pad = max(6, min((right - left) // 10, (bottom - top) // 10))
        padded_left = max(0, left - pad)
        padded_top = max(0, top - pad)
        padded_right = min(width, right + pad)
        padded_bottom = min(height, bottom + pad)
        region = img.crop((padded_left, padded_top, padded_right, padded_bottom))
        if crop_output_path is not None:
            crop_output_path.parent.mkdir(parents=True, exist_ok=True)
            region.save(crop_output_path)
            print(f"> Saved OCR region to {crop_output_path} for debugging")

        base_path = crop_output_path
        if base_path is None:
            base_path = TMP_DIR / "adb_ocr_crop_runtime.png"
            base_path.parent.mkdir(parents=True, exist_ok=True)
            region.save(base_path)

        best_text = _ocr_with_paddle(base_path, normalized_lang).strip()
        if best_text:
            return best_text

        # Retry with multiple preprocessed variants when first pass returns empty text.
        variants = _build_ocr_variants(region)
        for idx, variant in enumerate(variants, start=1):
            variant_path = TMP_DIR / f"adb_ocr_variant_{idx}.png"
            variant.save(variant_path)
            text = _ocr_with_paddle(variant_path, normalized_lang).strip()
            if text:
                print(f"> OCR fallback matched with variant {idx}: {variant_path}")
                return text
        return best_text


def text_contains(actual_text: str, expected_text: str) -> bool:
    normalized_actual = " ".join(actual_text.split()).lower()
    normalized_expected = " ".join(expected_text.split()).lower()
    return normalized_expected in normalized_actual

if __name__ == "__main__":
    # Example usage:
    preload_ocr_models(["zh"])
    import time
    time.sleep(1)  # Give some time for model loading to complete before running OCR.
    result = read_text_in_region(
        image_path=Path("tmp/adb_screen.png"),
        x1=141, y1=1880, x2=940, y2=2200,
        ocr_lang="zh"
    )
    print("OCR Result:", result)
    result = read_text_in_region(
        image_path=Path("tmp/adb_screen.png"),
        x1=141, y1=1880, x2=940, y2=2200,
        ocr_lang="zh"
    )
    print("OCR Result:", result)
