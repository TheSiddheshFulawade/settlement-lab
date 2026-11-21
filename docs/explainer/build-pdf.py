"""Build Settlement-Lab-LLD.pdf from lld.src.html and the SVG diagrams.

Animated SVGs are embedded as still images: moving elements are removed and CSS animations are
switched off, so every step of each diagram is visible in print.
Run from anywhere: python docs/explainer/build-pdf.py   (needs Microsoft Edge or Chrome)
"""
import base64
import pathlib
import re
import subprocess
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
FIGS = {
    "architecture": "architecture.svg",
    "flow": "flow.svg",
    "locking": "locking.svg",
    "breaker": "breaker.svg",
    "uimockup": "ui-mockup.svg",
    "uisequence": "ui-sequence.svg",
}
BROWSERS = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
]


def still(svg: str) -> str:
    svg = re.sub(r'<g class="anim">.*?</g>', "", svg, flags=re.S)
    return svg.replace("</style>", "*{animation:none!important}</style>", 1)


def data_uri(name: str) -> str:
    svg = still((HERE / name).read_text(encoding="utf-8"))
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode("utf-8")).decode("ascii")


def main() -> None:
    html = (HERE / "lld.src.html").read_text(encoding="utf-8")
    for key, name in FIGS.items():
        html = html.replace("{{" + key + "}}", data_uri(name))
    left = re.findall(r"\{\{\w+\}\}", html)
    if left:
        raise SystemExit(f"unreplaced placeholders: {left}")
    browser = next((b for b in BROWSERS if pathlib.Path(b).exists()), None)
    if not browser:
        raise SystemExit("Edge or Chrome not found")
    out = HERE / "Settlement-Lab-LLD.pdf"
    out.unlink(missing_ok=True)
    # Edge's launcher can return before the PDF is written, so wait for the file to appear and
    # stop growing before the temporary page is deleted.
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        page = pathlib.Path(tmp) / "lld.html"
        page.write_text(html, encoding="utf-8")
        subprocess.run(
            [browser, "--headless=new", "--disable-gpu", f"--user-data-dir={tmp}\\profile",
             "--no-pdf-header-footer", f"--print-to-pdf={out}", page.as_uri()],
            capture_output=True, timeout=120,
        )
        last = -1
        for _ in range(120):
            size = out.stat().st_size if out.exists() else -1
            if size > 0 and size == last:
                break
            last = size
            time.sleep(1)
        else:
            raise SystemExit("the browser did not write the PDF within 2 minutes")
    print(f"wrote {out} ({out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
