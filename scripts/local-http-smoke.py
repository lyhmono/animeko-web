#!/usr/bin/env python3
"""启动本机安装产物并验证HTTP契约；仅写独立测试目录，不请求第三方源。"""
from __future__ import annotations

import argparse
import base64
import http.client
import json
import os
import pathlib
import re
import socket
import subprocess
import tempfile
import time
from http.cookies import SimpleCookie


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--output', type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    launcher = root / 'backend/build/install/animeko-web/bin/animeko-web'
    if not launcher.is_file() or not (root / 'frontend/dist/index.html').is_file():
        parser.error('请先构建 backend installDist 和 frontend production dist')
    work = pathlib.Path(tempfile.mkdtemp(prefix='animeko-http-smoke-', dir=os.environ.get('TMPDIR')))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    env = {key: value for key, value in os.environ.items() if not key.startswith('ANIMEKO_')}
    env.update({
        'ANIMEKO_HOST': '127.0.0.1', 'ANIMEKO_PORT': str(port),
        'ANIMEKO_DATA_DIR': str(work / 'data'), 'ANIMEKO_FRONTEND_DIR': '../frontend/dist',
        'ANIMEKO_AUTH_ENABLED': 'true', 'ANIMEKO_AUTH_USER': 'local-fixture',
        'ANIMEKO_AUTH_PASSWORD': 'fixture-only-not-a-real-account',
        'ANIMEKO_SUBSCRIPTION_AUTO_IMPORT': '', 'ANIMEKO_SEED_SUBSCRIPTION_URL': '',
        'ANIMEKO_ALLOW_PRIVATE_TARGETS': 'false', 'ANIMEKO_COOKIE_SECURE': 'false',
        'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8',
    })
    if args.java_home:
        env['JAVA_HOME'] = args.java_home
        env['PATH'] = str(pathlib.Path(args.java_home) / 'bin') + os.pathsep + env.get('PATH', '')
    results = []

    def request(method, path, body=None, headers=None):
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
        payload = json.dumps(body) if body is not None else None
        try:
            connection.request(method, path, body=payload, headers={'Content-Type': 'application/json', **(headers or {})})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def expect(name, expected, method, path, body=None, headers=None):
        status, response_headers, payload = request(method, path, body, headers)
        if status != expected:
            raise AssertionError(f'{name}: 期望 {expected}，实际 {status}，响应 {payload[:200]!r}')
        results.append({'name': name, 'status': status})
        return response_headers, payload

    log = (work / 'server.log').open('w')
    server = subprocess.Popen([str(launcher)], cwd=root / 'backend', env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 40
        while True:
            if server.poll() is not None:
                raise RuntimeError(f'测试进程提前退出，日志 {work / "server.log"}')
            try:
                if request('GET', '/api/health')[0] == 200:
                    break
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError('40秒内健康接口未就绪')
            time.sleep(0.15)
        expect('健康检查', 200, 'GET', '/api/health')
        _, html = expect('相对静态目录与首页', 200, 'GET', '/')
        if b'id="root"' not in html:
            raise AssertionError('首页不是构建产物')
        assets = re.findall(rb'(?:src|href)="(/assets/[^"]+)"', html)
        if not assets:
            raise AssertionError('首页没有哈希静态资源')
        for asset in assets:
            expect('构建静态资源', 200, 'GET', asset.decode())
        expect('未登录数据门控', 401, 'GET', '/api/data')
        expect('编码路径门控', 401, 'GET', '/%61pi/data')
        headers, _ = expect('本机测试登录', 200, 'POST', '/api/session/login', {
            'username': env['ANIMEKO_AUTH_USER'], 'password': env['ANIMEKO_AUTH_PASSWORD'],
        })
        cookie_value = next(v for k, v in headers.items() if k.lower() == 'set-cookie')
        jar = SimpleCookie(cookie_value)
        name = next(k for k in jar if k.endswith('animeko_session'))
        token = jar[name].value
        cookie = {'Cookie': f'{name}={token}'}
        headers, _ = expect('登录后读取版本', 200, 'GET', '/api/data', headers=cookie)
        etag = next(v for k, v in headers.items() if k.lower() == 'etag')
        document = {'favorites': [{'id': 1, 'name': '本机合成夹具'}]}
        expect('条件保存', 200, 'PUT', '/api/data', document, {**cookie, 'If-Match': etag})
        expect('陈旧版本拒绝', 409, 'PUT', '/api/data', {}, {**cookie, 'If-Match': etag})
        _, saved = expect('保存后回读', 200, 'GET', '/api/data', headers=cookie)
        if json.loads(saved) != document:
            raise AssertionError('回读数据与提交不符')
        expect('跨站写拒绝', 403, 'PUT', '/api/data', {}, {**cookie, 'Origin': 'https://untrusted.example'})
        expect('私网目标拒绝', 403, 'GET', '/api/proxy?url=http%3A%2F%2F127.0.0.1%2Fprivate', headers=cookie)
        # 前面的成功登录已消耗一次未验证凭据许可；Cookie请求不消耗该预算。
        for index in range(7):
            expect(f'剩余凭据预算内失败{index + 1}', 401, 'POST', '/api/session/login', {'username': 'local-fixture', 'password': 'wrong-fixture'})
        expect('预算耗尽拒绝错误候选', 429, 'POST', '/api/session/login', {'username': 'local-fixture', 'password': 'wrong-fixture'})
        expect('预算耗尽也不验正确候选', 429, 'POST', '/api/session/login', {
            'username': env['ANIMEKO_AUTH_USER'], 'password': env['ANIMEKO_AUTH_PASSWORD'],
        })
        basic = base64.b64encode(f"{env['ANIMEKO_AUTH_USER']}:{env['ANIMEKO_AUTH_PASSWORD']}".encode()).decode()
        expect('Basic不绕过预算', 429, 'GET', '/api/data', headers={'Authorization': f'Basic {basic}'})
        expect('已有会话不被口令桶误锁', 200, 'GET', '/api/data', headers=cookie)
        expect('Bearer动态会话注销', 200, 'POST', '/api/session/logout', headers={'Authorization': f'Bearer {token}'})
        expect('注销后Cookie不可重放', 401, 'GET', '/api/data', headers=cookie)
        if server.poll() is not None:
            raise AssertionError('进程不是我们仍运行的测试实例')
    finally:
        server.terminate()
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
        log.close()
    output = {'passed': len(results), 'checks': results, 'workspace': str(work),
              'server_exit_after_cleanup': server.returncode,
              'scope': '真实本机JVM安装产物与HTTP；测试账号和数据为隔离夹具，未请求第三方源'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2))
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
