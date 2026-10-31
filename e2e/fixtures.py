"""浏览器回归专用固定夹具；不请求真实元数据源、不修改服务端个人数据。"""
from __future__ import annotations

import copy
import json
from urllib.parse import parse_qs, urlparse


class FixtureApi:
    def __init__(self):
        self.document = {
            'favorites': [
                {
                    'id': i, 'name': f'Fixture {i}', 'name_cn': f'回归夹具番剧 {i}',
                    'cover': '/favicon.svg', 'date': '2024-01-01', 'score': 8.1,
                    'status': 'watching', 'ts': i,
                }
                for i in range(1, 65)
            ],
        }
        self.revision = 1
        self.subjects = [
            {
                'id': i, 'name': f'Fixture {i}', 'name_cn': f'回归夹具番剧 {i}',
                'summary': '明确标记的浏览器测试数据，不代表真实 Bangumi 内容。',
                'date': '2024-01-01', 'score': 8.1,
                'rating': {'rank': i, 'score': 8.1, 'total': 100},
                'images': {'common': '/favicon.svg', 'large': '/favicon.svg'},
                'eps': [{'id': 1000 + i, 'name': '第1话', 'name_cn': '第1话', 'sort': 1, 'type': 0}],
            }
            for i in range(1, 73)
        ]

    def response(self, method, url, payload=None):
        parsed = urlparse(url)
        path, params = parsed.path, parse_qs(parsed.query)
        headers = {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}
        if path == '/api/health':
            return 200, {'status': 'ok', 'version': 'e2e-fixture', 'authEnabled': False, 'authenticated': True}, headers
        if path == '/api/data':
            if method == 'PUT':
                self.document = copy.deepcopy(payload or {})
                self.revision += 1
                return 200, {'bytes': len(json.dumps(self.document).encode())}, {**headers, 'ETag': f'"fixture-{self.revision}"'}
            return 200, copy.deepcopy(self.document), {**headers, 'ETag': f'"fixture-{self.revision}"'}
        if path == '/api/bangumi/calendar':
            days = [
                {'weekday': {'id': day, 'en': f'day-{day}', 'cn': f'星期{day}'},
                 'items': [{**subject, 'eps': 1} for subject in self.subjects[:4]]}
                for day in range(1, 8)
            ]
            return 200, days, headers
        if path == '/api/bangumi/ranking':
            offset = max(0, int(params.get('offset', ['0'])[0]))
            limit = min(50, max(1, int(params.get('limit', ['24'])[0])))
            return 200, {'total': len(self.subjects), 'offset': offset, 'limit': limit,
                         'data': copy.deepcopy(self.subjects[offset:offset + limit])}, headers
        if path == '/api/bangumi/search':
            return 200, copy.deepcopy(self.subjects[:6]), headers
        if path.startswith('/api/bangumi/subjects/'):
            target = int(path.rsplit('/', 1)[-1])
            subject = next((item for item in self.subjects if item['id'] == target), None)
            return (200, copy.deepcopy(subject), headers) if subject else (404, {'error': 'NOT_FOUND'}, headers)
        if path == '/api/bangumi/channel':
            return 200, {'channel': 'AUTO', 'customUrls': [], 'effectiveBases': ['https://fixture.invalid'],
                         'defaultBases': ['https://fixture.invalid'], 'calendarSupported': True}, headers
        if path == '/api/bangumi/channel/probe':
            return 200, [{'base': 'https://fixture.invalid', 'ok': True,
                          'calendarOk': True, 'latencyMs': 0, 'detail': '固定回归夹具'}], headers
        if path == '/api/selector/sources':
            return 200, [], headers
        if path == '/api/session/logout':
            return 200, {'authenticated': False}, headers
        return 404, {'error': 'UNEXPECTED_FIXTURE_ROUTE', 'path': path}, headers


def install_api_fixtures(context):
    """在浏览器 context 拦截全部 /api，避免偶然连到真实上游或写线上数据。"""
    api = FixtureApi()

    def handle(route):
        request = route.request
        payload = json.loads(request.post_data) if request.post_data else None
        status, body, headers = api.response(request.method, request.url, payload)
        route.fulfill(status=status, headers=headers, body=json.dumps(body, ensure_ascii=False))

    context.route('**/api/**', handle)
    return api
