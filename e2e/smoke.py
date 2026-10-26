"""浏览器级冒烟测试：确认页面真的渲染出来了，而不只是静态资源能取到。

为什么需要这一层：CI 之前只验到「HTML 里有 id=root」「JS 文件能取到 200」。
这挡不住下面这些问题，而它们全都真实出现过：
  - 组件根本没挂载（路由写错、懒加载路径写错）
  - CSS 类名写错（`.subject-name` 那种：元素在，但样式全丢）
  - 手机端底部导航没生效（媒体查询写错，桌面看着正常）
  - 备份卡片/继续观看这类新模块被条件渲染吞掉

约定：断言只用 data-testid，不依赖中文文案与 DOM 顺序 —— 文案会改、顺序会调，
这些不应该是「测试挂了」的理由。

用法：python e2e/smoke.py <base_url>
退出码 0 = 全部通过；非 0 = 有断言失败（并逐条打印 + 失败截图）。
"""

from __future__ import annotations

import sys

from playwright.sync_api import Page, sync_playwright, expect


BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18080"

failures: list[str] = []


class SkipCheck(Exception):
    """环境不满足断言前提（如页面不够高无法滚动）：显式 skip，不算通过。"""


def check(name: str, fn, page: Page) -> None:
    """跑一条断言。失败记下来继续跑下一条 —— 一次看到所有问题，
    比修一条跑一次快得多（CI 一轮好几分钟，来回几轮就废掉半小时）。
    失败时顺手截图：CI 里最想知道的是「它到底长什么样」，而这在日志里看不出来。
    """
    try:
        fn()
        print(f"  [ok] {name}")
    except SkipCheck as e:
        print(f"  [skip] {name}: {e}")
    except Exception as e:  # noqa: BLE001 - 断言失败类型很多，统一收集
        print(f"  [FAIL] {name}: {str(e).splitlines()[0][:300]}")
        failures.append(name)
        try:
            page.screenshot(path=f"e2e/{safe_name(name)}.png", full_page=True)
        except Exception:  # noqa: BLE001 - 截图失败不该掩盖原错误
            pass


def safe_name(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_" else "_" for c in name)[:60]


def css(page: Page, testid: str, prop: str) -> str:
    """读某个 testid 元素的计算样式。

    用 locator.evaluate 而不是 page.evaluate + 手拼选择器：后者要写嵌套引号转义，
    容易出错，也让「元素不存在」和「样式为空」两种情况混在一起。
    """
    loc = page.get_by_test_id(testid)
    if loc.count() == 0:
        raise AssertionError(f"找不到 data-testid={testid} 的元素")
    return loc.first.evaluate("(el, p) => getComputedStyle(el).getPropertyValue(p)", prop).strip()


def main() -> int:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 800})

        # ---------- 桌面 ----------
        print("desktop 1280x800")
        page.goto(BASE, wait_until="networkidle")

        check(
            "React 已挂载",
            lambda: expect(page.locator("#root > *").first).to_be_visible(timeout=15000),
            page,
        )
        check("顶栏导航存在", lambda: expect(page.get_by_test_id("nav-tabs")).to_be_visible(), page)
        check("首页 tab 存在", lambda: expect(page.get_by_test_id("nav-home")).to_be_visible(), page)
        def desktop_nav_at_top() -> None:
            """桌面端导航必须在**视口顶部**，而不是底部。

            不能再用「position != fixed」判断：桌面端现在也用 fixed
            （为了与 sticky 顶栏同步，见 .nav-tabs 的注释）。
            真正要验的是位置：rect.bottom 应该落在顶部 80px 以内。
            """
            r = page.get_by_test_id("nav-tabs").evaluate(
                "(el) => { const b = el.getBoundingClientRect(); return {top: b.top, bottom: b.bottom}; }"
            )
            if r["bottom"] > 80:
                raise AssertionError(
                    f"桌面端导航底边在 {r['bottom']:.0f}px，应贴在顶部（<80）"
                )

        check("桌面端导航在顶部", desktop_nav_at_top, page)
        def airing_ok() -> None:
            """每日放送要么渲染出来，要么明确因为上游不可用而隐藏 —— 两者都算对。

            不能强求「一定可见」：CI 的 IP 会被 bgmapi 镜像限流/拒绝（实测返回
            500 与 404），而「拉不到就整块隐藏」本身就是产品设计（镜像没有该端点）。
            这里要防的是第三种情况：组件在**有数据**时也没渲染出来。
            """
            el = page.get_by_test_id("airing-calendar")
            if el.count() > 0 and el.first.is_visible():
                return
            # 没渲染：请求上游看是「不可用」还是「我们自己的 bug」
            status = page.evaluate(
                "async () => { const r = await fetch('/api/bangumi/calendar'); return r.status; }"
            )
            if status >= 500:
                print(f"    (上游不可用 HTTP {status}，组件按设计隐藏)")
                return
            # 404 不是「上游挂了」：/api/bangumi/calendar 是我们自己定义的路由，
            # 它 404 只有两种可能——路由被改名/删掉，或者部署的是旧产物。
            # 原先把 404 一并归给上游，等于这条检查在路由消失时照样绿灯。
            if status == 404:
                raise AssertionError("每日放送接口 404：我们自己的路由不见了")
            raise AssertionError(f"接口正常({status})但每日放送没渲染")

        check("每日放送区块渲染", airing_ok, page)

        # ---------- 设置页：备份卡片 ----------
        print("settings")
        page.get_by_test_id("nav-settings").click()
        page.wait_for_url("**/settings")
        check("备份卡片渲染", lambda: expect(page.get_by_test_id("backup-card")).to_be_visible(), page)
        # 数据已加载（启动流程会等 loadUserData），导出按钮必须可用 ——
        # 若它还禁用着，说明「已加载」的判定坏了
        check(
            "导出按钮可用",
            lambda: expect(page.get_by_test_id("backup-export")).to_be_enabled(),
            page,
        )

        # ---------- 发现页：排行榜 ----------
        print("discover")
        page.get_by_test_id("nav-discover").click()
        page.wait_for_url("**/discover")
        check(
            "排行榜网格出现",
            # 后端 Bangumi 超时是 45 秒，镜像最坏情况下要等满；这里必须覆盖它
            lambda: expect(page.get_by_test_id("rank-grid")).to_be_visible(timeout=55000),
            page,
        )
        def ranking_ok() -> None:
            """有数据就必须渲染出来；空网格只在「上游确实没给数据」时放过。

            这里曾经误判过两次，都记在下面，避免以后又改回去：
            1. 先查 count() 再判断 —— rank-grid 首帧就存在（骨架屏阶段），
               此时 count 必然是 0，等于把「还没加载完」当成「不渲染」。
               必须先给足时间等卡片出现。
            2. rank-grid 这个 testid 一度挂在**骨架屏**那个 div 上（写错位置），
               而骨架屏里没有 <a>，于是数据即使渲染了也永远查不到卡片。
               现在 testid 在真实结果网格上。
            """
            cards = page.get_by_test_id("rank-grid").locator("a")
            try:
                expect(cards.first).to_be_visible(timeout=50000)
                return
            except Exception:  # noqa: BLE001 - 超时后去对账上游是否本来就没数据
                pass

            # 接口 200 + data 为空，同样是「上游没数据」而不是我们的 bug：
            # bgmapi 镜像对 CI 出口 IP 既会返 5xx，也会返 200 + 空数组。
            body = page.evaluate(
                """async () => {
                  const r = await fetch('/api/bangumi/ranking');
                  // 只有 5xx 才是「上游没数据」。404 是我们自己的路由问题，
                  // 401/403 是门控问题，都不该被算成 count=-1 然后判定为预期
                  if (!r.ok && r.status >= 500) return { status: r.status, count: -1 };
                  if (!r.ok) return { status: r.status, count: 0, broken: true };
                  const j = await r.json();
                  return { status: r.status, count: (j.data || []).length };
                }"""
            )
            if body.get("broken"):
                raise AssertionError(
                    f"排行榜接口回 {body['status']}，不是上游故障而是我们自己的门控/路由问题"
                )
            if body["count"] <= 0:
                print(f"    (上游未返回数据：HTTP {body['status']}、{body['count']} 条，空榜属预期)")
                return
            raise AssertionError(
                f"接口返回 {body['count']} 条条目，但页面一张卡片都没渲染（真 bug）"
            )

        check("排行榜有结果", ranking_ok, page)

        # ---------- 收藏页 ----------
        print("favorites")
        page.get_by_test_id("nav-favorites").click()
        page.wait_for_url("**/favorites")
        # 空收藏时只有空态、没有状态 tabs，所以断言「主体在」而不是 tabs，
        # 否则首次部署（0 收藏）会得到一条假失败
        check("收藏页渲染", lambda: expect(page.get_by_test_id("main")).to_be_visible(), page)

        def nav_follows_scroll() -> None:
            """向下滚动后，导航项必须还贴在顶栏里。

            这是真实存在过的缺陷：.nav 是 sticky（滚到顶就贴住视口），
            而 .nav-tabs 当时是 absolute 相对 .app 定位 —— 页面一滚，
            顶栏留下了、导航项却跟着内容滚走了。
            断言方式：滚动 400px，再量导航的 rect.top，应该还在顶部附近。
            """
            # 页面不够高时 window.scrollTo 不会产生滚动，下面的 rect 断言就会空跑。
            can_scroll = page.evaluate(
                "() => document.documentElement.scrollHeight > window.innerHeight + 400"
            )
            if not can_scroll:
                raise SkipCheck("当前页面不够高，无法验证滚动后的导航")
            page.evaluate("() => window.scrollTo(0, 400)")
            page.wait_for_timeout(300)
            y = page.evaluate("() => window.scrollY")
            if y <= 0:
                raise AssertionError("scrollTo(0,400) 后 window.scrollY 仍是 0，滚动断言无效")
            r = page.get_by_test_id("nav-tabs").evaluate(
                "(el) => el.getBoundingClientRect().top"
            )
            if r > 40:
                raise AssertionError(f"滚动 400px 后导航跑到了 {r:.0f}px（顶栏还在，导航滚走了）")
            page.evaluate("() => window.scrollTo(0, 0)")

        check("滚动后导航仍贴顶", nav_follows_scroll, page)

        # ---------- 中等宽度：顶栏不该挤在一起 ----------
        print("tablet 900x700")
        t = browser.new_page(viewport={"width": 900, "height": 700})
        t.goto(BASE, wait_until="networkidle")

        def no_overlap() -> None:
            """顶栏里各区块不能重叠。

            这条专门防「加了导航却没管中间宽度」：5 个文字链接约占 378px，
            与 320px 的搜索框在 900px 视口下会叠在一起 —— 而原来的断点只有
            720px 与 640px，721-1100px 这段完全没人管。
            量法：拿每个区块的 rect，两两比较横向区间是否相交。
            """
            rects = {}
            for tid in ("nav-logo", "nav-search", "nav-tabs", "nav-actions"):
                loc = t.get_by_test_id(tid)
                if loc.count() == 0:
                    continue
                r = loc.first.evaluate(
                    "(el) => { const b = el.getBoundingClientRect(); return {l: b.left, r: b.right}; }"
                )
                rects[tid] = (r["l"], r["r"])

            order = [k for k in ("nav-logo", "nav-search", "nav-tabs", "nav-actions") if k in rects]
            for a, b in zip(order, order[1:]):
                if rects[a][1] > rects[b][0] + 1:
                    raise AssertionError(
                        f"{a} 右边缘 {rects[a][1]:.0f} 压住了 {b} 的左边缘 {rects[b][0]:.0f}"
                    )

        check("顶栏各区块不重叠", no_overlap, t)

        # ---------- 手机 ----------
        print("mobile 390x844")
        m = browser.new_page(
            viewport={"width": 390, "height": 844},
            is_mobile=True,
            has_touch=True,
            device_scale_factor=3,
        )
        m.goto(BASE, wait_until="networkidle")

        check(
            "手机端导航固定",
            lambda: assert_true(
                css(m, "nav-tabs", "position") == "fixed",
                f"手机端 position={css(m, 'nav-tabs', 'position')}，应为 fixed",
            ),
            m,
        )

        def nav_at_bottom() -> None:
            # 用 getBoundingClientRect 而不是 bounding_box：后者在 fixed 元素上
            # 返回的 now 是布局盒（可能被 flex 拉满），rect 才是相对视口的真实位置
            r = m.get_by_test_id("nav-tabs").evaluate(
                "(el) => { const b = el.getBoundingClientRect(); return {top: b.top, bottom: b.bottom}; }"
            )
            # 容差 40px：底部还有 env(safe-area-inset-bottom)，模拟器是 0 但真机可能吃掉一截
            if abs(r["bottom"] - 844) > 40:
                raise AssertionError(f"导航底边 {r['bottom']:.0f} 离视口底 844 太远")

        check("手机端导航贴底", nav_at_bottom, m)

        def main_not_covered() -> None:
            """底栏的实际占位高度要和 main 的底部内边距比。

            不能用 bounding_box().height：底栏是 position:fixed 且 left/right:0，
            在某些布局下（父级是 flex column）它的盒子高度会被拉成整个视口，
            实测拿到过 834px —— 那样比出来的结论完全没意义。
            改用量 scrollHeight：内容实际占的高度，这才是「会挡住多少内容」。
            """
            bar_h = float(
                m.get_by_test_id("nav-tabs").evaluate(
                    "(el) => el.getBoundingClientRect().height"
                )
            )
            # 底栏不该高过 200px：超过说明它被拉伸成整屏了（曾经真的发生过 ——
            # 桌面规则里的 top:10px 没在移动端被覆盖，top+bottom 同时存在把元素拉满）
            if bar_h > 200:
                raise AssertionError(f"底栏高度 {bar_h:.0f}px 异常，疑似被拉伸成整屏")
            pad = float(
                m.get_by_test_id("main").evaluate(
                    "(el) => parseFloat(getComputedStyle(el).paddingBottom)"
                )
            )
            if pad < bar_h:
                raise AssertionError(f"main 底部内边距 {pad}px < 底栏内容高度 {bar_h:.0f}px")

        check("内容为底栏留出空间", main_not_covered, m)

        m.goto(f"{BASE}/play", wait_until="domcontentloaded")
        check(
            "播放页隐藏底部导航",
            lambda: expect(m.get_by_test_id("nav-tabs")).to_be_hidden(timeout=10000),
            m,
        )

        # ---------- PWA ----------
        print("pwa")
        info = page.evaluate(
            """async () => {
              const r = await fetch('/manifest.webmanifest');
              return { ct: r.headers.get('content-type'), body: await r.json() };
            }"""
        )
        check(
            "manifest 可解析",
            lambda: assert_true("start_url" in info["body"], "manifest 缺少 start_url"),
            page,
        )
        check(
            "manifest Content-Type 正确",
            lambda: assert_true(
                "manifest+json" in (info["ct"] or ""), f"实际: {info['ct']}"
            ),
            page,
        )
        # 看番要能横屏：锁竖屏会让装到桌面的应用没法横过来
        check(
            "manifest 未锁竖屏",
            lambda: assert_true(
                info["body"].get("orientation") != "portrait-primary",
                "不要把 PWA 锁在竖屏",
            ),
            page,
        )

        browser.close()

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {', '.join(failures)}")
        return 1
    print("ALL PASSED")
    return 0


def assert_true(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


if __name__ == "__main__":
    sys.exit(main())