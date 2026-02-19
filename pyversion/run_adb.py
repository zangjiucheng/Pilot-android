import argparse
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

try:
    from PIL import Image
except ImportError:  # Pillow is optional unless color checks are used
    Image = None  # type: ignore[assignment]

from ocr import (
    DEFAULT_OCR_LANG,
    OCR_CROP_PATH,
    normalize_ocr_lang,
    preload_ocr_models,
    read_text_in_region,
    text_contains,
)

SCRIPT_DIR = Path(__file__).resolve().parent
TMP_DIR = SCRIPT_DIR.parent / "tmp"
SCREENSHOT_PATH = TMP_DIR / "adb_screen.png"


@dataclass
class JumpDirective:
    label: Optional[str] = None
    is_call: bool = False
    is_return: bool = False
    auto_return: bool = False
    should_exit: bool = False


@dataclass
class BranchAction:
    label: Optional[str] = None
    is_call: bool = False
    should_exit: bool = False


@dataclass
class LabelBounds:
    start: int
    end: int


@dataclass
class CallFrame:
    return_index: int
    label: str
    auto_return: bool = False


class CommandExecutionError(RuntimeError):
    """Raised when an ADB command fails and execution should stop."""


def capture_screenshot(destination: Path = SCREENSHOT_PATH) -> Path:
    """Capture a screenshot from the connected Android device."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as target:
        subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            check=True,
            stdout=target,
        )
    return destination


def parse_rgb(value: str) -> Tuple[int, int, int]:
    value = value.strip()
    if value.startswith("#"):
        hex_value = value[1:]
        if len(hex_value) != 6:
            raise ValueError("hex colors must be provided as #RRGGBB")
        return tuple(int(hex_value[i : i + 2], 16) for i in range(0, 6, 2))

    parts = [part.strip() for part in value.split(",")]
    if len(parts) == 3:
        rgb = tuple(int(part) for part in parts)
        if all(0 <= channel <= 255 for channel in rgb):
            return rgb

    raise ValueError(
        "expected a color in #RRGGBB or R,G,B format (e.g. #ff0000 or 255,0,0)"
    )


def read_pixel_color(image_path: Path, x: int, y: int) -> Tuple[int, int, int]:
    if Image is None:
        raise RuntimeError(
            "Pillow is required when using CHECK_COLOR. Install with `pip install pillow`."
        )
    with Image.open(image_path) as img:
        width, height = img.size
        if not (0 <= x < width and 0 <= y < height):
            raise ValueError(
                f"coordinates ({x}, {y}) are outside the screenshot bounds {width}x{height}"
            )
        rgb_image = img.convert("RGB")
        return tuple(rgb_image.getpixel((x, y)))


def parse_branch_tokens(tokens: List[str]) -> Tuple[Optional[BranchAction], Optional[BranchAction]]:
    on_match: Optional[BranchAction] = None
    on_mismatch: Optional[BranchAction] = None
    i = 0
    while i < len(tokens):
        keyword = tokens[i].upper()
        if keyword not in {"THEN", "ELSE"}:
            raise ValueError(f"Expected THEN or ELSE keyword, got '{tokens[i]}'")
        i += 1
        if i >= len(tokens):
            raise ValueError(f"{keyword} must be followed by a label name")

        token_upper = tokens[i].upper()
        if token_upper == "EXIT":
            action = BranchAction(should_exit=True)
        else:
            is_call = False
            if token_upper == "CALL":
                is_call = True
                i += 1
                if i >= len(tokens):
                    raise ValueError(f"{keyword} CALL must be followed by a label name")
                token_upper = tokens[i].upper()

            if token_upper == "GOTO":
                i += 1
                if i >= len(tokens):
                    raise ValueError(f"{keyword} GOTO must be followed by a label name")
                label = tokens[i]
            else:
                label = tokens[i]

            action = BranchAction(label=label, is_call=is_call)

        if keyword == "THEN":
            on_match = action
        else:
            on_mismatch = action
        i += 1
    return on_match, on_mismatch


def branch_target_to_directive(target: Optional[BranchAction]) -> Optional[JumpDirective]:
    if target is None:
        return None
    if target.should_exit:
        return JumpDirective(should_exit=True)
    return JumpDirective(label=target.label, is_call=target.is_call)


def check_color(command: str) -> Optional[JumpDirective]:
    tokens = shlex.split(command)
    if len(tokens) < 4:
        print(
            "!! CHECK_COLOR usage: CHECK_COLOR <x> <y> <#RRGGBB|R,G,B> [tolerance] "
            "[THEN [CALL|GOTO] <label>] [ELSE [CALL|GOTO] <label>]"
        )
        return None

    _, x_str, y_str, *rest = tokens
    try:
        x = int(x_str)
        y = int(y_str)
    except ValueError as exc:
        print(f"!! Invalid CHECK_COLOR coordinates: {exc}")
        return None

    if not rest:
        print("!! CHECK_COLOR missing color argument")
        return None

    def parse_required_tolerance(value: str) -> int:
        try:
            tolerance_value = int(value)
        except ValueError as exc:  # keep original error for context
            raise ValueError("tolerance must be an integer") from exc
        if tolerance_value < 0:
            raise ValueError("tolerance must be >= 0")
        return tolerance_value

    def consume_optional_tolerance(remaining: List[str]) -> Tuple[int, List[str]]:
        if not remaining:
            return 0, remaining
        try:
            value = int(remaining[0])
        except ValueError:
            return 0, remaining
        if value < 0:
            raise ValueError("tolerance must be >= 0")
        return value, remaining[1:]

    remainder = rest[:]
    tolerance = 0
    expected: Optional[Tuple[int, int, int]] = None

    try:
        expected = parse_rgb(remainder[0])
        remainder = remainder[1:]
        tolerance, remainder = consume_optional_tolerance(remainder)
    except ValueError as color_error:
        try:
            tolerance = parse_required_tolerance(remainder[0])
        except ValueError:
            print(f"!! Invalid CHECK_COLOR arguments: {color_error}")
            return None
        if len(remainder) < 2:
            print("!! CHECK_COLOR missing color after tolerance")
            return None
        try:
            expected = parse_rgb(remainder[1])
        except ValueError as exc:
            print(f"!! Invalid CHECK_COLOR color: {exc}")
            return None
        remainder = remainder[2:]

    branch_tokens = remainder

    try:
        on_match, on_mismatch = parse_branch_tokens(branch_tokens)
    except ValueError as exc:
        print(f"!! Invalid CHECK_COLOR branching syntax: {exc}")
        return None

    print(f"> Checking pixel ({x}, {y}) against {expected} ±{tolerance}")
    try:
        screenshot = capture_screenshot()
        actual = read_pixel_color(screenshot, x, y)
    except Exception as exc:
        print(f"!! Failed to capture or inspect screenshot: {exc}")
        return branch_target_to_directive(on_mismatch)

    matches = all(abs(a - b) <= tolerance for a, b in zip(actual, expected))
    if matches:
        print(f"✓ Pixel matches expected color. Actual={actual}")
        return branch_target_to_directive(on_match)

    print(f"!! Pixel mismatch. Actual={actual}, Expected={expected} ±{tolerance}")
    return branch_target_to_directive(on_mismatch)


def check_ocr(command: str) -> Optional[JumpDirective]:
    tokens = shlex.split(command)
    if len(tokens) < 6:
        print(
            "!! CHECK_OCR usage: CHECK_OCR <x1> <y1> <x2> <y2> <text> "
            "[LANG <language>] "
            "[THEN [CALL|GOTO] <label>] [ELSE [CALL|GOTO] <label>]"
        )
        return None

    _, x1_str, y1_str, x2_str, y2_str, *rest = tokens
    try:
        x1 = int(x1_str)
        y1 = int(y1_str)
        x2 = int(x2_str)
        y2 = int(y2_str)
    except ValueError as exc:
        print(f"!! Invalid CHECK_OCR coordinates: {exc}")
        return None

    if not rest:
        print("!! CHECK_OCR missing text argument")
        return None

    expected_text = rest[0]
    remainder = rest[1:]
    ocr_lang = DEFAULT_OCR_LANG

    if len(remainder) >= 2 and remainder[0].upper() == "LANG":
        ocr_lang = normalize_ocr_lang(remainder[1])
        remainder = remainder[2:]

    branch_tokens = remainder
    try:
        on_match, on_mismatch = parse_branch_tokens(branch_tokens)
    except ValueError as exc:
        print(f"!! Invalid CHECK_OCR branching syntax: {exc}")
        return None

    print(
        f"> Checking OCR region ({x1}, {y1}, {x2}, {y2}) contains "
        f"{expected_text!r} using lang={ocr_lang!r}"
    )
    try:
        screenshot = capture_screenshot()
        actual_text = read_text_in_region(
            screenshot,
            x1,
            y1,
            x2,
            y2,
            ocr_lang=ocr_lang,
            crop_output_path=OCR_CROP_PATH,
        )
    except Exception as exc:
        print(f"!! Failed to capture screenshot or run OCR: {exc}")
        return branch_target_to_directive(on_mismatch)

    matches = text_contains(actual_text, expected_text)
    if matches:
        print(f"✓ OCR match found. Text={actual_text!r}")
        return branch_target_to_directive(on_match)

    print(
        "!! OCR text not found. "
        f"Expected substring={expected_text!r}, OCR={actual_text!r}"
    )
    return branch_target_to_directive(on_mismatch)


def is_within_bounds(index: int, bounds: LabelBounds) -> bool:
    return bounds.start <= index < bounds.end


def pop_auto_frame_if_leaving(
    call_stack: List[CallFrame],
    label_bounds: Dict[str, LabelBounds],
    current_index: int,
    target_index: int,
) -> None:
    if not call_stack:
        return

    frame = call_stack[-1]
    if not frame.auto_return:
        return

    bounds = label_bounds.get(frame.label)
    if bounds is None:
        call_stack.pop()
        return

    if is_within_bounds(current_index, bounds) and not is_within_bounds(target_index, bounds):
        call_stack.pop()


def adjust_for_auto_return(
    line_index: int,
    call_stack: List[CallFrame],
    label_bounds: Dict[str, LabelBounds],
) -> int:
    while call_stack and call_stack[-1].auto_return:
        frame = call_stack[-1]
        bounds = label_bounds.get(frame.label)
        if bounds is None or not is_within_bounds(line_index, bounds):
            line_index = frame.return_index
            call_stack.pop()
            continue
        break
    return line_index


def send_home_and_sleep() -> None:
    """Return to the home screen and put the device to sleep at the end of the run."""
    actions = [
        (["adb", "shell", "input", "keyevent", "3"], "home button"),
        (["adb", "shell", "input", "keyevent", "KEYCODE_SLEEP"], "sleep button"),
    ]
    for command, description in actions:
        try:
            subprocess.run(command, check=True)
            time.sleep(0.2)
        except Exception as exc:
            print(f"!! Failed to send {description}: {exc}")


def run(cmd: str) -> Optional[JumpDirective]:
    cmd = cmd.strip()
    if not cmd or cmd.startswith("#"):
        return None  # skip empty lines or commented lines

    normalized = cmd.upper()
    if normalized.startswith("LABEL"):
        return None
    if normalized.startswith("GOTO"):
        tokens = shlex.split(cmd)
        if len(tokens) < 2:
            print("!! GOTO usage: GOTO <label>")
            return None
        return JumpDirective(label=tokens[1])
    if normalized.startswith("JUMP"):
        tokens = shlex.split(cmd)
        if len(tokens) < 2:
            print("!! JUMP usage: JUMP <label>")
            return None
        return JumpDirective(label=tokens[1], is_call=True, auto_return=True)
    if normalized.startswith("CALL"):
        tokens = shlex.split(cmd)
        if len(tokens) < 2:
            print("!! CALL usage: CALL <label>")
            return None
        return JumpDirective(label=tokens[1], is_call=True)
    if normalized.startswith("RETURN"):
        return JumpDirective(is_return=True)
    if normalized.startswith("EXIT"):
        return JumpDirective(should_exit=True)
    if normalized.startswith("SLEEP"):
        tokens = shlex.split(cmd)
        duration = 1.0
        if len(tokens) >= 2:
            try:
                duration = float(tokens[1])
            except ValueError:
                print(f"!! Invalid SLEEP duration '{tokens[1]}', using 1 second")
        print(f"> Sleeping for {duration} seconds")
        time.sleep(max(0.0, duration))
        return None
    if normalized.startswith("CHECK_COLOR"):
        return check_color(cmd)
    if normalized.startswith("CHECK_OCR"):
        return check_ocr(cmd)

    print(f"> Running: {cmd}")
    try:
        subprocess.run(shlex.split(cmd), check=True)
    except Exception as exc:
        print(f"!! Error running command: {cmd}\n{exc}")
        raise CommandExecutionError(f"Command failed: {cmd}") from exc
    return None


def build_label_map(lines: List[str]) -> Tuple[Dict[str, int], Dict[str, LabelBounds]]:
    labels: Dict[str, int] = {}
    bounds: Dict[str, LabelBounds] = {}
    current_label: Optional[str] = None

    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.upper().startswith("LABEL"):
            continue

        tokens = shlex.split(stripped)
        if len(tokens) < 2:
            print(f"!! LABEL missing name on line {index + 1}")
            continue

        label_name = tokens[1]
        if label_name in labels:
            print(f"!! Duplicate LABEL '{label_name}' (keeping first definition)")
            continue

        labels[label_name] = index
        bounds[label_name] = LabelBounds(start=index, end=len(lines))

        if current_label is not None:
            bounds[current_label].end = index
        current_label = label_name

    return labels, bounds


def collect_ocr_langs(lines: List[str]) -> List[str]:
    langs: List[str] = []
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if not stripped.upper().startswith("CHECK_OCR"):
            continue

        try:
            tokens = shlex.split(stripped)
        except ValueError:
            # Keep runtime behavior for malformed command lines.
            continue

        if len(tokens) < 6:
            continue

        lang = DEFAULT_OCR_LANG
        remainder = tokens[6:]
        if len(remainder) >= 2 and remainder[0].upper() == "LANG":
            lang = normalize_ocr_lang(remainder[1])
        langs.append(lang)

    if not langs:
        return [DEFAULT_OCR_LANG]
    return langs


def main() -> None:
    parser = argparse.ArgumentParser(description="Run ADB automation commands from a file.")
    parser.add_argument(
        "commands_file",
        nargs="?",
        default="commands.txt",
        help="Path to commands file (default: commands.txt)",
    )
    args = parser.parse_args()

    commands_path = Path(args.commands_file)
    if not commands_path.exists():
        print(f"!! Commands file not found: {commands_path}")
        raise SystemExit(2)

    print(f"> Using commands file: {commands_path}")
    with commands_path.open("r", encoding="utf-8") as command_file:
        lines = command_file.readlines()

    ocr_langs = collect_ocr_langs(lines)
    print(f"> Preloading OCR models for languages: {', '.join(ocr_langs)}")
    try:
        preload_ocr_models(ocr_langs)
    except Exception as exc:
        print(f"!! Failed to preload OCR models: {exc}")
        raise SystemExit(2) from exc

    labels, label_bounds = build_label_map(lines)
    line_index = 0
    call_stack: List[CallFrame] = []
    try:
        while line_index < len(lines):
            current_index = line_index
            directive = run(lines[line_index])
            time.sleep(0.3)  # small delay to prevent overloading ADB

            if directive is None:
                line_index = adjust_for_auto_return(line_index + 1, call_stack, label_bounds)
                continue

            if directive.is_return:
                if not call_stack:
                    print("!! RETURN called with an empty call stack")
                    line_index = adjust_for_auto_return(line_index + 1, call_stack, label_bounds)
                    continue
                frame = call_stack.pop()
                line_index = adjust_for_auto_return(frame.return_index, call_stack, label_bounds)
                continue

            if directive.should_exit:
                time.sleep(0.5)
                print("> EXIT received. Stopping command processing.")
                break

            if directive.label is None:
                line_index = adjust_for_auto_return(line_index + 1, call_stack, label_bounds)
                continue

            target = labels.get(directive.label)
            if target is None:
                print(f"!! Unknown label '{directive.label}'")
                line_index = adjust_for_auto_return(line_index + 1, call_stack, label_bounds)
                continue

            if directive.is_call or directive.auto_return:
                call_stack.append(
                    CallFrame(
                        return_index=current_index + 1,
                        label=directive.label,
                        auto_return=directive.auto_return,
                    )
                )
            else:
                pop_auto_frame_if_leaving(call_stack, label_bounds, current_index, target)

            line_index = adjust_for_auto_return(target, call_stack, label_bounds)
    except CommandExecutionError as exc:
        print(f"!! Automation halted: {exc}")
        raise SystemExit(1) from exc
    finally:
        send_home_and_sleep()


if __name__ == "__main__":
    main()
