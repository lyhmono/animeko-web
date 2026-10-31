"""不依赖浏览器安装，验证原始冒烟断言不会吞掉后端 5xx。"""
from __future__ import annotations

import ast
import pathlib
import unittest

SOURCE = pathlib.Path(__file__).with_name('smoke.py')


class HiddenLocator:
    @property
    def first(self):
        return self

    def count(self):
        return 0

    def is_visible(self):
        return False

    def locator(self, _selector):
        return self


class FailedRenderingExpectation:
    def to_be_visible(self, **_kwargs):
        raise AssertionError('夹具数据没有渲染')


class PageFixture:
    def __init__(self, status):
        self.status = status

    def get_by_test_id(self, _testid):
        return HiddenLocator()

    def evaluate(self, script):
        if 'count:' in script:
            return {'status': self.status, 'count': -1}
        return self.status


def original_assertion(name, page):
    tree = ast.parse(SOURCE.read_text())
    node = next(n for n in ast.walk(tree) if isinstance(n, ast.FunctionDef) and n.name == name)
    module = ast.Module(body=[node], type_ignores=[])
    namespace = {'page': page, 'expect': lambda _loc: FailedRenderingExpectation()}
    exec(compile(ast.fix_missing_locations(module), str(SOURCE), 'exec'), namespace)
    return namespace[name]


class SmokeFailureContractTest(unittest.TestCase):
    def test_calendar_server_error_does_not_pass_as_rendered(self):
        with self.assertRaises(AssertionError):
            original_assertion('airing_ok', PageFixture(500))()

    def test_ranking_server_error_does_not_pass_as_empty_fixture(self):
        with self.assertRaises(AssertionError):
            original_assertion('ranking_ok', PageFixture(502))()

    def test_calendar_missing_route_stays_failure(self):
        with self.assertRaises(AssertionError):
            original_assertion('airing_ok', PageFixture(404))()


if __name__ == '__main__':
    unittest.main()
