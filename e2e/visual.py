"""可选的视觉回归工具（不作为 CI 门禁）。

用法（先起好服务，与 e2e/smoke.py 相同）：
  python e2e/visual.py --update   # 生成或更新基线
  python e2e/visual.py            # 与基线逐页比较

为什么不做成 CI 门禁：封面图、字体抗锯齿、上游数据差异都会让像素级 diff
误报。它的用途是「改样式前后各跑一次，人工确认差异是不是自己想要的」。
"""
import os
import pathlib
import sys

from playwright.sync_api import sync_playwright

from smoke import install_api_fixtures

PAGES = [
    ('/', 'home'),
    ('/discover', 'discover'),
    ('/search?q=%E9%AC%BC%E7%81%AD', 'search'),
    ('/favorites', 'favorites'),
    ('/settings', 'settings'),
]
ROOT = pathlib.Path(__file__).parent
BASELINE = ROOT / 'visual-baseline'
OUT = ROOT / 'visual-out'


def main() -> int:
    update = '--update' in sys.argv
    base = os.environ.get('BASE', 'http://127.0.0.1:8080')
    BASELINE.mkdir(exist_ok=True)
    OUT.mkdir(exist_ok=True)
    diffs = []
    with sync_playwright() as p:
        browser = p.chromium.launch()
        for path, name in PAGES:
            page = browser.new_page(viewport={'width': 1280, 'height': 800}, device_scale_factor=1)
            install_api_fixtures(page.context)
            page.goto(base + path, wait_until='networkidle')
            target = OUT / f'{name}.png'
            page.screenshot(path=str(target), full_page=True, animations='disabled')
            baseline = BASELINE / f'{name}.png'
            if update or not baseline.exists():
                baseline.write_bytes(target.read_bytes())
                print(f'baseline written: {name}')
            elif baseline.read_bytes() != target.read_bytes():
                diffs.append(name)
                print(f'DIFF: {name}（对比 {baseline} 与 {target}）')
            else:
                print(f'same: {name}')
            page.close()
        browser.close()
    return 1 if diffs and not update else 0


if __name__ == '__main__':
    sys.exit(main())